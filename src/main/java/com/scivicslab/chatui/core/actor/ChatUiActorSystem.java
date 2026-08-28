package com.scivicslab.chatui.core.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.logging.ForwardingAccumulator;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;
import com.scivicslab.chatui.openaicompat.OpenAiCompatProvider;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.plugins.logoutput.MultiplexerAccumulator;
import com.scivicslab.turingworkflow.plugins.logoutput.MultiplexerAccumulatorActor;
import com.scivicslab.turingworkflow.plugins.logoutput.MultiplexerLogHandler;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.RootIIAR;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Actor-system holder for {@code chat-ui-with-audit-trail}.
 *
 * <p>Builds one {@link IIActorSystem} whose auto-created {@link RootIIAR} carries a
 * {@link ConversationTab} per open conversation, and exposes the tree through
 * {@link #getActorTree()} for the Actors tab.</p>
 *
 * <p>Each {@link ConversationTab} owns one {@link ChatSessionIIAR} (wrapping a
 * {@link ChatSession}) and one {@link PromptQueue}, wired per
 * {@code ChatSessionIIAR_260810_oo01} "ConversationTab への接続" — stage 1: no agent loop,
 * no StallMonitor, {@code openai-compat} only (see {@code ChatSessionPorting_260823_oo01}).</p>
 */
@ApplicationScoped
public class ChatUiActorSystem {

    private static final Logger LOG = Logger.getLogger(ChatUiActorSystem.class.getName());

    // Field initializers (not just @ConfigProperty defaultValue) so plain `new ChatUiActorSystem()`
    // in a pure unit test — no CDI container, per this project's testing policy — still has usable
    // values; CDI overwrites these when the bean is actually managed.
    @ConfigProperty(name = "chat-ui.servers")
    List<String> servers = List.of("http://localhost:8000");

    @ConfigProperty(name = "chat-ui.default-model", defaultValue = "default")
    String defaultModel = "default";

    @Inject
    IoLogStore ioLogStore;

    // Field initializer for the no-CDI unit-test path (see the servers/defaultModel comment above).
    ObjectMapper objectMapper = new ObjectMapper();

    /** Name of the system-wide log multiplexer actor — fixed, per {@code MultiplexerLogHandler}'s
     *  hardcoded lookup name. */
    private static final String SYSTEM_LOG_ACTOR = "outputMultiplexer";
    private static final int SYSTEM_LOG_CAPACITY = 500;
    private static final int TAB_LOG_CAPACITY = 200;

    private IIActorSystem actorSystem;
    private final Map<String, ActorRef<ConversationTab>> tabs = new ConcurrentHashMap<>();
    private final Map<String, ChatSessionIIAR> chatSessions = new ConcurrentHashMap<>();
    private RecentEntriesAccumulator systemLogBuffer;
    private final Map<String, RecentEntriesAccumulator> tabLogBuffers = new ConcurrentHashMap<>();
    private ActorRef<CallWatchdog> callWatchdogRef;
    private ActorRef<CollaborationGraph> collaborationGraphRef;

    /**
     * Initializes the actor system and seeds a small tree so the Actors tab has something to show.
     */
    @PostConstruct
    void init() {
        actorSystem = new IIActorSystem("chat-ui");

        // System-wide log multiplexer (150_TabScopedLogging_260826_oo01): the top of the chat-log
        // hierarchy, and MultiplexerLogHandler's hardcoded forwarding target for framework/non-actor
        // log records (Quarkus startup, HTTP layer, etc.) that never go through a ConversationTab.
        systemLogBuffer = new RecentEntriesAccumulator(SYSTEM_LOG_CAPACITY);
        MultiplexerAccumulator systemMux = new MultiplexerAccumulator();
        systemMux.addTarget(systemLogBuffer);
        actorSystem.addIIActor(new MultiplexerAccumulatorActor(SYSTEM_LOG_ACTOR, systemMux, actorSystem));
        MultiplexerLogHandler logHandler = new MultiplexerLogHandler(actorSystem);
        logHandler.setLevel(Level.ALL);
        // Excludes loggers that already reach outputMultiplexer via an explicit path (ChatSession/
        // PromptQueue's own logToTab() calls, forwarded through their tab's own multiplexer) — one
        // content stream, one path, matching the proven RunCLI.java wiring (explicit multiplexer.add
        // for primary content; this root-logger bridge only for content with no other path). Without
        // this filter every one of those log lines reached outputMultiplexer twice.
        Set<String> explicitlyForwardedLoggers = Set.of(ChatSession.class.getName(), PromptQueue.class.getName());
        logHandler.setFilter(record -> {
            String loggerName = record.getLoggerName();
            return loggerName == null || !explicitlyForwardedLoggers.contains(loggerName);
        });
        Logger.getLogger("").addHandler(logHandler);

        // ask_chat cross-tab tool support (AskChatToolAndWatchdog_260827_oo01): one CallWatchdog for
        // the whole system, refusing ask_chat calls that would create a circular wait.
        callWatchdogRef = actorSystem.getRoot().createChild("callWatchdog", new CallWatchdog());

        // Graph-engineering role assignments (CollaborationGraph_260828_oo01): one CollaborationGraph
        // for the whole system, so workflow logic (babysitter loops etc.) can resolve a collaborator
        // tab by role instead of a hardcoded chat id.
        collaborationGraphRef = actorSystem.getRoot().createChild("collaborationGraph", new CollaborationGraph());

        createTab("01");
        createTab("02");
        LOG.info("Actor system initialised with " + tabs.size() + " conversation tabs");
    }

    /**
     * @param tabId conversation tab identifier
     * @return that tab's recent log entries (oldest first), or {@code null} if the tab does not exist
     */
    public List<RecentEntriesAccumulator.Entry> getTabLogEntries(String tabId) {
        RecentEntriesAccumulator buf = tabLogBuffers.get(tabId);
        return buf == null ? null : buf.recent();
    }

    /** @return the system-wide log's recent entries (oldest first) */
    public List<RecentEntriesAccumulator.Entry> getSystemLogEntries() {
        return systemLogBuffer.recent();
    }

    /**
     * Creates, or returns the existing, {@link ConversationTab} for {@code tabId} as a child of
     * ROOT, together with its {@link ChatSessionIIAR} and {@link PromptQueue}.
     *
     * @param tabId conversation tab identifier
     * @return the tab's actor reference
     */
    public synchronized ActorRef<ConversationTab> createTab(String tabId) {
        ActorRef<ConversationTab> existing = tabs.get(tabId);
        if (existing != null) {
            return existing;
        }
        ActorRef<ConversationTab> tabRef =
                actorSystem.getRoot().createChild("chat-" + tabId, new ConversationTab());
        tabs.put(tabId, tabRef);

        // Tab log multiplexer (150_TabScopedLogging_260826_oo01): this tab's own recent-entries
        // buffer, plus delegation up to the system-wide multiplexer via ForwardingAccumulator.
        String tabLogActorName = tabRef.getName() + ".log";
        RecentEntriesAccumulator tabLogBuffer = new RecentEntriesAccumulator(TAB_LOG_CAPACITY);
        tabLogBuffers.put(tabId, tabLogBuffer);
        MultiplexerAccumulator tabMux = new MultiplexerAccumulator();
        tabMux.addTarget(tabLogBuffer);
        tabMux.addTarget(new ForwardingAccumulator(actorSystem, SYSTEM_LOG_ACTOR, tabId));
        actorSystem.addIIActor(new MultiplexerAccumulatorActor(tabLogActorName, tabMux, actorSystem));

        // ChatSessionIIAR — manual IIActorRef bridge, since ConversationTab is a plain POJO and
        // cannot call addChildActor itself (ChatSessionIIAR_260810_oo01 "ConversationTab への接続").
        OpenAiCompatProvider provider = new OpenAiCompatProvider(servers, defaultModel);
        ChatSessionIIAR chatSessionIIAR = new ChatSessionIIAR(
                tabRef.getName() + ".chat", provider, Optional.empty(), ioLogStore, actorSystem);
        chatSessionIIAR.setParentName(tabRef.getName());
        tabRef.getNamesOfChildren().add(chatSessionIIAR.getName());
        actorSystem.addIIActor(chatSessionIIAR);
        chatSessions.put(tabId, chatSessionIIAR);
        chatSessionIIAR.tell(a -> ((ChatSession) a).setTabId(tabId));
        chatSessionIIAR.tell(a -> ((ChatSession) a).setWatchdogRef(callWatchdogRef));
        chatSessionIIAR.tell(a -> ((ChatSession) a).setCollaborationGraphRef(collaborationGraphRef));

        // provider child — created by the generating side, not by ChatSession itself
        // (ChatSessionPorting_260823_oo01 "なぜ init を無くしたか").
        LlmProvider providerAsLlmProvider = provider;
        ActorRef<LlmProvider> providerRef = chatSessionIIAR.<LlmProvider>createChild(
                chatSessionIIAR.getName() + ".provider", providerAsLlmProvider);
        chatSessionIIAR.tell(a -> ((ChatSession) a).setProviderName(providerRef.getName()));

        // PromptQueue — plain createChild, same as any other ConversationTab sibling.
        ActorRef<PromptQueue> promptQueueRef =
                tabRef.createChild(tabRef.getName() + ".queue", new PromptQueue());
        chatSessionIIAR.tell(a -> ((ChatSession) a).setPromptQueueName(promptQueueRef.getName()));
        // Lets PromptQueue's own dispatch-request handlers (enqueue/onPromptComplete/advance)
        // hand the actual queue.remove(0) back to its own actor thread via self.ask(...) instead
        // of mutating `queue` directly from ChatSession's thread (see PromptQueueThreadSafety
        // fix — mirrors how ChatSession receives its own setActorSystem/setProviderName).
        promptQueueRef.tell(q -> q.setSelf(promptQueueRef));
        promptQueueRef.tell(q -> q.setLogging(actorSystem, tabLogActorName));

        // SseConnection — plain createChild, same as PromptQueue (ChatResourceDesign_260823_oo01).
        tabRef.createChild(tabRef.getName() + ".sse", new SseConnection(objectMapper));

        return tabRef;
    }

    /**
     * Returns the {@link ActorRef} for {@code tabId}'s {@link PromptQueue}, or {@code null} if
     * the tab does not exist.
     *
     * @param tabId conversation tab identifier
     * @return the queue's actor reference, or {@code null}
     */
    public ActorRef<PromptQueue> getPromptQueue(String tabId) {
        return actorSystem.getActor("chat-" + tabId + ".queue");
    }

    /**
     * Returns the {@link ActorRef} for {@code tabId}'s {@link SseConnection}, or {@code null} if
     * the tab does not exist.
     *
     * @param tabId conversation tab identifier
     * @return the connection's actor reference, or {@code null}
     */
    public ActorRef<SseConnection> getSseConnection(String tabId) {
        return actorSystem.getActor("chat-" + tabId + ".sse");
    }

    /**
     * Returns the {@link ChatSessionIIAR} for {@code tabId}, or {@code null} if none was created.
     *
     * @param tabId conversation tab identifier
     * @return the session's IIActorRef, or {@code null}
     */
    public ChatSessionIIAR getChatSession(String tabId) {
        return chatSessions.get(tabId);
    }

    /**
     * Returns the {@link ConversationTab} for {@code tabId}, or {@code null} if none was created.
     *
     * @param tabId conversation tab identifier
     * @return the tab's actor reference, or {@code null}
     */
    public ActorRef<ConversationTab> getTab(String tabId) {
        return tabs.get(tabId);
    }

    /**
     * Returns the ids of all conversation tabs created so far (insertion order not guaranteed —
     * callers that need a stable display order should sort).
     *
     * @return tab ids
     */
    public List<String> getTabIds() {
        return new ArrayList<>(tabs.keySet());
    }

    /**
     * Builds the actor tree from ROOT for the Actors tab.
     *
     * @return the root {@link ActorNode}
     */
    public ActorNode getActorTree() {
        if (actorSystem == null) {
            return new ActorNode("chat-ui", "IIActorSystem", false, List.of());
        }
        RootIIAR root = actorSystem.getRoot();
        return buildActorNode(root.getName(), root);
    }

    private ActorNode buildActorNode(String name, ActorRef<?> ref) {
        String type;
        try {
            @SuppressWarnings("unchecked")
            ActorRef<Object> r = (ActorRef<Object>) ref;
            type = r.askNow(o -> o.getClass().getSimpleName()).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            type = "?";
        }
        List<ActorNode> children = new ArrayList<>();
        for (String childName : new TreeSet<>(ref.getNamesOfChildren())) {
            ActorRef<?> childRef = actorSystem.getActor(childName);
            if (childRef == null) {
                // ChatSessionIIAR etc. live in iiActors, not actors (ChatSessionIIAR_260810_oo01
                // "getActorTree() に必要な追随修正").
                childRef = actorSystem.getIIActor(childName);
            }
            if (childRef == null) {
                continue;
            }
            children.add(buildActorNode(childName, childRef));
        }
        return new ActorNode(name, type, ref.isAlive(), children);
    }
}
