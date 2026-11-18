package com.scivicslab.chatui.core.remote;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.distributed.AllowListDispatcher.AllowedAction;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;
import com.scivicslab.pojoactor.distributed.FileMessageIdStore;
import com.scivicslab.pojoactor.distributed.NodeInfo;
import com.scivicslab.pojoactor.distributed.Receipt;
import com.scivicslab.pojoactor.distributed.RemoteActorRef;
import com.scivicslab.pojoactor.distributed.discovery.StaticNodeDiscovery;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The other chat-ui instances this one sends requests to and receives them from
 * ({@code ConversationSquadsAcrossInstances_261115_oo01}). Holds a small distributed actor system of its own that
 * listens on 127.0.0.1 and lets in only the actions of {@link #ALLOWED}, all on one actor, {@link #GATEWAY}, which
 * hands each message to this instance's conversations. The conversations themselves stay in this instance's own
 * actor system.
 *
 * <p>Started only when there is a source of peers: {@code chat-ui.peers} naming at least one node
 * ({@code name=host:port}), or the AI-workspace at {@code chat-ui.workspace-url}; otherwise there is no remote path at
 * all. Only registered nodes are sent to; a node the workspace no longer lists is unregistered at the next refresh.
 * A message the same node sent before, under the same message id, is run once.</p>
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

    /** The start of the answer to a send to a node that is not registered. */
    public static final String NODE_NOT_KNOWN = "node not known";

    private static final Map<IIActorSystem, RemoteNodes> BY_SYSTEM = new ConcurrentHashMap<>();

    /** How often the peer list is read again from the workspace. */
    public static final long WORKSPACE_REFRESH_SECONDS = 60;

    private final IIActorSystem local;
    private final DistributedIIActorSystem node;
    private final FileMessageIdStore messageIds;
    /** The workspace's peer list, or {@code null} when the peers were set by hand in {@code chat-ui.peers}. */
    private final WorkspaceNodeDiscovery workspace;
    private com.scivicslab.pojoactor.core.scheduler.Scheduler refresher;
    private com.scivicslab.pojoactor.core.scheduler.Scheduler announcer;

    private RemoteNodes(IIActorSystem local, DistributedIIActorSystem node, FileMessageIdStore messageIds,
                        WorkspaceNodeDiscovery workspace) {
        this.local = local;
        this.node = node;
        this.messageIds = messageIds;
        this.workspace = workspace;
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
        return start(local, peers, null, myName, actorPort, messageIdFile);
    }

    /**
     * Starts the remote path from where the peer list comes: the {@code name=host:port} entries of
     * {@code chat-ui.peers} when there are any (a list set by hand comes first, and the workspace is not asked), or
     * else the AI-workspace at {@code workspaceUrl}, read now, every {@link #WORKSPACE_REFRESH_SECONDS} seconds, and
     * whenever a node not yet in the list is named. With neither, there is no remote path.
     *
     * @param workspaceUrl the value of {@code chat-ui.workspace-url}, or {@code null} or blank when there is none
     * @return the started remote path, or {@code null} when there is no source of peers
     */
    public static RemoteNodes start(IIActorSystem local, String peers, String workspaceUrl, String myName, int actorPort,
                                    Path messageIdFile) throws java.io.IOException {
        NodeInfo me = new NodeInfo(myName, "127.0.0.1", actorPort);
        List<NodeInfo> byHand = StaticNodeDiscovery.parsePeers(nodeEntries(peers));
        com.scivicslab.pojoactor.distributed.discovery.NodeDiscovery discovery;
        WorkspaceNodeDiscovery workspace = null;
        if (!byHand.isEmpty()) {
            discovery = new StaticNodeDiscovery(me, byHand);
        } else if (workspaceUrl != null && !workspaceUrl.isBlank()) {
            workspace = new WorkspaceNodeDiscovery(me, workspaceUrl.strip());
            workspace.refresh();
            discovery = workspace;
        } else {
            return null;
        }
        FileMessageIdStore ids = new FileMessageIdStore(messageIdFile, DEDUP_MAX_ENTRIES, DEDUP_MAX_AGE);
        DistributedIIActorSystem node = DistributedIIActorSystem.builder()
                .discovery(discovery)
                .allowedActions(ALLOWED)
                .deduplicateWith(ids)
                .callTimeout(STATE_TIMEOUT)
                .build();
        RemoteNodes remote = new RemoteNodes(local, node, ids, workspace);
        // The discovery gives the system its chat-ui peers; the tool nodes of the same read are registered here.
        if (workspace != null) {
            for (ToolNode t : workspace.toolNodes()) node.registerRemoteNode(t.name(), t.host(), t.actorPort());
        }
        node.addIIActor(new RemoteGatewayIIAR(GATEWAY, new RemoteGateway(local, remote), node));
        node.startHttpServer("127.0.0.1", actorPort);
        if (workspace != null) remote.startRefreshing();
        BY_SYSTEM.put(local, remote);
        LOG.info("Remote path on 127.0.0.1:" + actorPort + " as node " + myName + "; peers "
                + (workspace == null ? "set by hand: " + byHand : "from the workspace " + workspaceUrl + ": " + workspace.peers()));
        return remote;
    }

    /** Reads the workspace's list every {@link #WORKSPACE_REFRESH_SECONDS} seconds, as a scheduled actor message. */
    private void startRefreshing() {
        com.scivicslab.pojoactor.core.ActorRef<RemoteNodes> self = node.actorOf("chat-ui-peer-list", this);
        refresher = new com.scivicslab.pojoactor.core.scheduler.Scheduler(1);
        refresher.scheduleWithFixedDelay("chat-ui-peer-list", self, RemoteNodes::refreshPeers,
                WORKSPACE_REFRESH_SECONDS, WORKSPACE_REFRESH_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Reads the workspace's list again: every node in it is registered, and every registered node no longer in it is
     * unregistered, so a send to it is refused at once with {@link #NODE_NOT_KNOWN}. A node that comes back is
     * registered again by the next refresh. Nothing when the peers were set by hand, or when the workspace cannot be
     * reached (the last list stays).
     */
    public synchronized void refreshPeers() {
        if (workspace == null || !workspace.refresh()) return;
        List<NodeInfo> peers = workspace.peers();
        List<ToolNode> tools = workspace.toolNodes();
        Set<String> listed = new java.util.HashSet<>();
        peers.forEach(p -> listed.add(p.getNodeId()));
        tools.forEach(t -> listed.add(t.name()));
        for (NodeInfo n : node.getNodes()) {
            if (!n.getNodeId().equals(myName()) && !listed.contains(n.getNodeId())) node.unregisterRemoteNode(n.getNodeId());
        }
        for (NodeInfo peer : peers) {
            node.registerRemoteNode(peer.getNodeId(), peer.getHost(), peer.getPort());
        }
        for (ToolNode t : tools) {
            node.registerRemoteNode(t.name(), t.host(), t.actorPort());
        }
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

    /**
     * A node of the distributed actor system that is not a chat-ui instance: the AI-workspace, or an html-saurus
     * portal, as the AI-workspace's {@code GET /api/nodes} lists it ({@code ActorNodesForWorkspaceAndPortal_261118_oo01}).
     */
    public record ToolNode(String name, String host, int actorPort, String kind) {}

    /** @return the tool nodes of {@code kind}, lowest actor port first; empty when the peers were set by hand */
    public List<ToolNode> toolNodes(String kind) {
        if (workspace == null) return List.of();
        return workspace.toolNodes().stream().filter(n -> n.kind().equals(kind))
                .sorted(java.util.Comparator.comparingInt(ToolNode::actorPort)).toList();
    }

    /**
     * Calls the gateway {@code gateway} of the tool node {@code nodeName} and waits for the result, at most
     * {@code timeout}. A node not in the last list makes this instance read the list again first.
     *
     * @return the result, or a failure saying {@link #NODE_NOT_KNOWN} when the workspace does not list that node
     */
    public ActionResult callTool(String nodeName, String gateway, String action, String json, Duration timeout) {
        if (!isToolNode(nodeName)) {
            refreshPeers();
            if (!isToolNode(nodeName)) return new ActionResult(false, NODE_NOT_KNOWN + ": " + nodeName);
        }
        try {
            return node.getRemoteActor(nodeName, gateway).callByActionName(action, json, timeout);
        } catch (RuntimeException e) {
            return new ActionResult(false, action + " on " + nodeName + " failed: " + e.getMessage());
        }
    }

    private boolean isToolNode(String name) {
        return workspace != null && workspace.toolNodes().stream().anyMatch(n -> n.name().equals(name));
    }

    /**
     * Starts announcing this instance to the AI-workspace named in {@code aiWorkspaceNode} (the value of
     * {@code AI_WORKSPACE_NODE}), now and then on schedule ({@link WorkspaceAnnouncer}). Nothing when there is no such
     * value, or when the peers were set by hand and no AI-workspace's lists are read.
     *
     * @param httpPort this instance's HTTP port
     * @return whether announcing started
     */
    public synchronized boolean announceTo(String aiWorkspaceNode, int httpPort) {
        Optional<String> ws = WorkspaceAnnouncer.parse(aiWorkspaceNode);
        if (ws.isEmpty() || workspace == null || announcer != null) return false;
        WorkspaceAnnouncer a = new WorkspaceAnnouncer(this, ws.get(), httpPort, System::currentTimeMillis);
        com.scivicslab.pojoactor.core.ActorRef<WorkspaceAnnouncer> self = node.actorOf(WorkspaceAnnouncer.ACTOR, a);
        announcer = new com.scivicslab.pojoactor.core.scheduler.Scheduler(1);
        // Ticks every RETRY_MILLIS; tick itself decides whether it is time to send.
        announcer.scheduleWithFixedDelay(WorkspaceAnnouncer.ACTOR, self, WorkspaceAnnouncer::tick, 0,
                WorkspaceAnnouncer.RETRY_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        return true;
    }

    /** @return the port this instance's node listens on */
    public int actorPort() {
        return node.getMyNode().getPort();
    }

    /** @return this instance's node name */
    public String myName() {
        return node.getMyNode().getNodeId();
    }

    /**
     * @return whether {@code name} is a node this instance knows. A name not in the workspace's list makes this
     *         instance read the list again first, so an instance started later is reached without a restart.
     */
    public boolean knows(String name) {
        if (listed(name)) return true;
        if (workspace == null) return false;
        refreshPeers();
        return listed(name);
    }

    private boolean listed(String name) {
        return others().contains(name);
    }

    /** @return the answer to a send to {@code nodeName}, which is not registered */
    public String notKnown(String nodeName) {
        return NODE_NOT_KNOWN + ": " + nodeName + "; the nodes this instance knows are " + others();
    }

    /** @return the names of the other chat-ui instances: the registered nodes less this one and the tool nodes */
    public List<String> others() {
        return node.getNodes().stream().map(NodeInfo::getNodeId)
                .filter(n -> !n.equals(myName()) && !isToolNode(n)).toList();
    }

    /**
     * Sends one message to another instance's {@link #GATEWAY}, trying again under the same message id until it is
     * received, at most {@link #SEND_ATTEMPTS} times.
     *
     * @return the receipt of the last try, or a refusal saying {@link #NODE_NOT_KNOWN} when the node is not
     *         registered, without any try
     */
    public Receipt send(String nodeName, String action, String json) {
        RemoteActorRef gateway = gatewayOf(nodeName);
        if (gateway == null) return Receipt.refused(null, notKnown(nodeName));
        return gateway.sendWithRetry(action, json, SEND_ATTEMPTS, SEND_DELAY);
    }

    /**
     * Calls another instance's {@link #GATEWAY} and waits for the result: only for short reads. A node that is not
     * registered is answered at once with a failure saying {@link #NODE_NOT_KNOWN}.
     */
    public ActionResult call(String nodeName, String action, String json) {
        RemoteActorRef gateway = gatewayOf(nodeName);
        if (gateway == null) return new ActionResult(false, notKnown(nodeName));
        return gateway.callByActionName(action, json, STATE_TIMEOUT);
    }

    /**
     * The one place a reference to another instance is made, and only to a registered node. The lookup and the
     * reference are one step, so a refresh that unregisters the node just before gives "not known", not an exception.
     *
     * @return the other instance's {@link #GATEWAY}, or {@code null} when {@code nodeName} is not registered
     */
    private RemoteActorRef gatewayOf(String nodeName) {
        if (nodeName == null || nodeName.equals(myName()) || isToolNode(nodeName)) return null;
        try {
            return node.getRemoteActor(nodeName, GATEWAY);
        } catch (IllegalArgumentException notRegistered) {
            return null;
        }
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
        if (refresher != null) refresher.close();
        if (announcer != null) announcer.close();
        BY_SYSTEM.remove(local, this);
        node.close();
        messageIds.close();
    }
}
