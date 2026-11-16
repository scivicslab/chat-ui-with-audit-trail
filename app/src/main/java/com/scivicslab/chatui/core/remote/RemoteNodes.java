package com.scivicslab.chatui.core.remote;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.distributed.AllowListDispatcher.AllowedAction;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;
import com.scivicslab.pojoactor.distributed.FileMessageIdStore;
import com.scivicslab.pojoactor.distributed.NodeInfo;
import com.scivicslab.pojoactor.distributed.Receipt;
import com.scivicslab.pojoactor.distributed.discovery.StaticNodeDiscovery;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The other chat-ui instances this one sends requests to and receives them from
 * ({@code ConversationSquadsAcrossInstances_261115_oo01}). Holds a small distributed actor system of its own that
 * listens on 127.0.0.1 and lets in only the actions of {@link #ALLOWED}, all on one actor, {@link #GATEWAY}, which
 * hands each message to this instance's conversations. The conversations themselves stay in this instance's own
 * actor system.
 *
 * <p>Started only when {@code chat-ui.peers} names at least one node ({@code name=host:port}); otherwise there is no
 * remote path at all. A message the same node sent before, under the same message id, is run once.</p>
 */
public final class RemoteNodes implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RemoteNodes.class.getName());

    /** The one actor other instances may call, in the small actor system. */
    public static final String GATEWAY = "chat-ui-remote";

    /** The actions other instances may call on {@link #GATEWAY}: the seven of the design. */
    public static final List<String> ACTIONS = List.of("enqueueRequest", "enqueueReply", "enqueueFollowUp",
            "withdrawRequest", "enqueueCancelResult", "registerPromise", "readTurnState");

    /** The allow-list: {@link #GATEWAY} with each of {@link #ACTIONS}, and nothing else. */
    public static final Set<AllowedAction> ALLOWED = ACTIONS.stream()
            .map(a -> new AllowedAction(GATEWAY, a)).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** How many message ids are remembered for dropping a second copy. */
    public static final int DEDUP_MAX_ENTRIES = 10_000;

    /** How long a message id is remembered for dropping a second copy. */
    public static final Duration DEDUP_MAX_AGE = Duration.ofDays(7);

    /** How many times a message is sent before giving up, and the wait between two tries. */
    public static final int SEND_ATTEMPTS = 3;
    public static final Duration SEND_DELAY = Duration.ofSeconds(1);

    /** How long reading another instance's turn state may take. */
    public static final Duration STATE_TIMEOUT = Duration.ofSeconds(5);

    private static final Map<IIActorSystem, RemoteNodes> BY_SYSTEM = new ConcurrentHashMap<>();

    private final IIActorSystem local;
    private final DistributedIIActorSystem node;
    private final FileMessageIdStore messageIds;

    private RemoteNodes(IIActorSystem local, DistributedIIActorSystem node, FileMessageIdStore messageIds) {
        this.local = local;
        this.node = node;
        this.messageIds = messageIds;
    }

    /**
     * Starts the remote path, or does nothing when no node is configured.
     *
     * @param local       this instance's actor system, where the conversations live
     * @param peers       the value of {@code chat-ui.peers}; only its {@code name=host:port} entries are nodes
     * @param myName      this instance's node name
     * @param actorPort   the port the small actor system listens on, on 127.0.0.1
     * @param messageIdFile where the message ids are remembered across a restart
     * @return the started remote path, or {@code null} when {@code peers} names no node
     */
    public static RemoteNodes start(IIActorSystem local, String peers, String myName, int actorPort, Path messageIdFile)
            throws java.io.IOException {
        List<NodeInfo> nodes = StaticNodeDiscovery.parsePeers(nodeEntries(peers));
        if (nodes.isEmpty()) return null;
        FileMessageIdStore ids = new FileMessageIdStore(messageIdFile, DEDUP_MAX_ENTRIES, DEDUP_MAX_AGE);
        DistributedIIActorSystem node = DistributedIIActorSystem.builder()
                .discovery(new StaticNodeDiscovery(new NodeInfo(myName, "127.0.0.1", actorPort), nodes))
                .allowedActions(ALLOWED)
                .deduplicateWith(ids)
                .callTimeout(STATE_TIMEOUT)
                .build();
        RemoteNodes remote = new RemoteNodes(local, node, ids);
        node.addIIActor(new RemoteGatewayIIAR(GATEWAY, new RemoteGateway(local, remote), node));
        node.startHttpServer("127.0.0.1", actorPort);
        BY_SYSTEM.put(local, remote);
        LOG.info("Remote path on 127.0.0.1:" + actorPort + " as node " + myName + "; peers " + nodes);
        return remote;
    }

    /** @return the {@code name=host:port} entries of {@code chat-ui.peers}; the others are for {@code OtherInstances} */
    static String nodeEntries(String peers) {
        if (peers == null) return "";
        return java.util.Arrays.stream(peers.split(",")).map(String::strip).filter(e -> e.contains("="))
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** @return the remote path of {@code local}, or {@code null} when it has none */
    public static RemoteNodes of(IIActorSystem local) {
        return local == null ? null : BY_SYSTEM.get(local);
    }

    /** @return this instance's node name */
    public String myName() {
        return node.getMyNode().getNodeId();
    }

    /** @return whether {@code name} is a node this instance knows */
    public boolean knows(String name) {
        return node.getNodes().stream().anyMatch(n -> n.getNodeId().equals(name) && !n.getNodeId().equals(myName()));
    }

    /** @return the names of the other nodes */
    public List<String> others() {
        return node.getNodes().stream().map(NodeInfo::getNodeId).filter(n -> !n.equals(myName())).toList();
    }

    /**
     * Sends one message to another instance's {@link #GATEWAY}, trying again under the same message id until it is
     * received, at most {@link #SEND_ATTEMPTS} times.
     *
     * @return the receipt of the last try
     */
    public Receipt send(String nodeName, String action, String json) {
        return node.getRemoteActor(nodeName, GATEWAY).sendWithRetry(action, json, SEND_ATTEMPTS, SEND_DELAY);
    }

    /** Calls another instance's {@link #GATEWAY} and waits for the result: only for short reads. */
    public ActionResult call(String nodeName, String action, String json) {
        return node.getRemoteActor(nodeName, GATEWAY).callByActionName(action, json, STATE_TIMEOUT);
    }

    /**
     * Reads, for the timekeeper's notice, whether a conversation of another instance is running a turn: the one call
     * on the remote path that waits for its answer, and only for {@link #STATE_TIMEOUT}.
     *
     * @return the state, or {@link com.scivicslab.chatui.core.actor.Timekeeper.TargetState#UNKNOWN} when it cannot be read
     */
    public static com.scivicslab.chatui.core.actor.Timekeeper.TargetState turnStateOf(IIActorSystem local, String nodeName,
                                                                                      String target) {
        RemoteNodes remote = of(local);
        if (remote == null || !remote.knows(nodeName)) return com.scivicslab.chatui.core.actor.Timekeeper.TargetState.UNKNOWN;
        try {
            ActionResult r = remote.call(nodeName, "readTurnState", new org.json.JSONObject().put("target", target).toString());
            if (!r.isSuccess()) return com.scivicslab.chatui.core.actor.Timekeeper.TargetState.UNKNOWN;
            String state = r.getResult();
            if (state.startsWith("running:")) {
                return new com.scivicslab.chatui.core.actor.Timekeeper.TargetState(true, true,
                        Long.parseLong(state.substring("running:".length())));
            }
            return new com.scivicslab.chatui.core.actor.Timekeeper.TargetState(true, false, 0);
        } catch (RuntimeException e) {
            return com.scivicslab.chatui.core.actor.Timekeeper.TargetState.UNKNOWN;
        }
    }

    /** @return this instance's own actor system, where its conversations live */
    IIActorSystem local() {
        return local;
    }

    @Override
    public void close() {
        BY_SYSTEM.remove(local, this);
        node.close();
        messageIds.close();
    }
}
