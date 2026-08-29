package com.scivicslab.chatui.core.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.agent.RunPlanTool;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.logging.ForwardingAccumulator;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;
import com.scivicslab.chatui.openaicompat.OpenAiCompatProvider;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.plugins.logoutput.MultiplexerAccumulator;
import com.scivicslab.turingworkflow.plugins.logoutput.MultiplexerAccumulatorActor;
import com.scivicslab.turingworkflow.plugins.logoutput.MultiplexerLogHandler;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
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

    /** Project id of the project created at startup ({@code Terminology_260829_oo01}). */
    public static final String DEFAULT_PROJECT_ID = "project1";

    private IIActorSystem actorSystem;
    /** Keyed by qualified chat name ({@code project1/chat-01}), the same string used in the registry. */
    private final Map<String, ActorRef<ConversationTab>> chats = new ConcurrentHashMap<>();
    private final Map<String, ChatSessionIIAR> chatSessions = new ConcurrentHashMap<>();
    private RecentEntriesAccumulator systemLogBuffer;
    private final Map<String, RecentEntriesAccumulator> chatLogBuffers = new ConcurrentHashMap<>();

    private ActorRef<CallWatchdog> callWatchdogRef;
    private ActorRef<CollaborationGraph> collaborationGraphRef;

    /** One {@link Project} grouping actor per project id. Purely an actor-tree grouping plus a
     *  naming prefix: a project is not a behavioral boundary
     *  ({@code ProjectScopedActorTree_260829_oo01}, {@code ProjectNamespacePrefix_260829_oo01}). */
    private final Map<String, ActorRef<Project>> projects = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger nextProjectNumber =
            new java.util.concurrent.atomic.AtomicInteger(2);

    /**
     * The registry name of a conversation's {@link ConversationTab} actor: the project id, a
     * {@code /}, then {@code chat-} and the chat id ({@code Terminology_260829_oo01} "アクター名").
     *
     * @param projectId the owning project's id, e.g. {@code "project1"}
     * @param chatId    the conversation's id within that project, e.g. {@code "01"}
     * @return e.g. {@code "project1/chat-01"}
     */
    public static String chatActorName(String projectId, String chatId) {
        return projectId + "/chat-" + chatId;
    }

    /**
     * Initializes the actor system and seeds a small tree so the Actors tab has something to show.
     */
    @PostConstruct
    void init() {
        actorSystem = new IIActorSystem("chat-ui");

        // System-wide log multiplexer (150_TabScopedLogging_260826_oo01): the top of the chat-log
        // hierarchy, and MultiplexerLogHandler's hardcoded forwarding target for framework/non-actor
        // log records (Quarkus startup, HTTP layer, etc.) that never go through a ConversationTab.
        // Deliberately outside any Project's subtree — see ProjectScopedActorTree_260829_oo01 "なぜ
        // outputMultiplexerはプロジェクトごとに分離しないか": MultiplexerLogHandler's lookup name is
        // hardcoded, so only one such actor can ever exist, and framework logs (e.g. Quarkus startup)
        // aren't inherently scoped to any one project anyway.
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
        // the whole system, refusing ask_chat calls that would create a circular wait. Deliberately
        // NOT per-project — a wait chain spans projects whenever a cross-project ask_chat happens, and
        // detecting a cycle needs the whole chain in one place (ProjectScopedActorTree_260829_oo01
        // "なぜCallWatchdog・CollaborationGraphもプロジェクトごとに分離しないか").
        callWatchdogRef = actorSystem.getRoot().createChild("callWatchdog", new CallWatchdog());

        // Graph-engineering role assignments (CollaborationGraph_260828_oo01): likewise one for the
        // whole system — split per project, a set_collaborator naming another project's tab would be
        // written to one graph and read from another, silently losing the assignment.
        collaborationGraphRef = actorSystem.getRoot().createChild("collaborationGraph", new CollaborationGraph());

        // Default (first) project: only chat-01 exists at startup — chat-02, chat-03, ... are
        // created lazily as work actually needs them (ProjectScopedActorTree_260829_oo01 "開始状態
        // が違う"), not pre-seeded.
        projects.put(DEFAULT_PROJECT_ID,
                actorSystem.getRoot().createChild(DEFAULT_PROJECT_ID, new Project()));
        createChat(DEFAULT_PROJECT_ID, "01");
        LOG.info("Actor system initialised with " + chats.size() + " conversation(s)");
    }

    /**
     * Creates a new project — its own {@link Project} grouping actor and that project's first
     * conversation. Projects group conversations in the actor tree and prefix their names; they
     * are not isolation boundaries, so a conversation may still {@code ask_chat} one in another
     * project.
     *
     * @return the new project's id (e.g. {@code "project2"}); its first conversation is {@code "01"}
     */
    public synchronized String createProject() {
        String projectId = "project" + nextProjectNumber.getAndIncrement();
        projects.put(projectId, actorSystem.getRoot().createChild(projectId, new Project()));
        createChat(projectId, "01");
        return projectId;
    }

    /** @return the ids of all projects created so far */
    public List<String> getProjectIds() {
        return new ArrayList<>(projects.keySet());
    }

    /**
     * Resolves the target of a cross-conversation tool call ({@code ask_chat}/{@code set_workflow})
     * into a qualified conversation name. A bare id ({@code "02"}) names a conversation in the
     * caller's own project; a qualified one ({@code "project2/02"}) names one in another project,
     * so crossing a project boundary is visible in the argument itself
     * ({@code ProjectNamespacePrefix_260829_oo01}).
     *
     * @param callerProjectId the calling conversation's project id
     * @param target          the {@code chatId} argument as written by the caller
     * @return e.g. {@code "project1/chat-02"}
     */
    public static String resolveChatName(String callerProjectId, String target) {
        int slash = target.indexOf('/');
        return slash < 0
                ? chatActorName(callerProjectId, target)
                : chatActorName(target.substring(0, slash), target.substring(slash + 1));
    }

    /**
     * Starts a plan on a conversation without waiting for it — the entry point for a plan written
     * outside this system and handed in over REST, rather than by a conversation's own LLM
     * ({@code DirectPlanSubmission_260830_oo01}). The result is written to that conversation's log
     * when it arrives, since nobody is blocked waiting for it.
     *
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @param yaml      the plan, as Turing-workflow YAML text
     * @return {@code null} on success, or an {@code error: ...} string
     */
    public String submitPlan(String projectId, String chatId, String yaml) {
        String chatName = chatActorName(projectId, chatId);
        String logActor = chatName + ".log";
        return RunPlanTool.submit(actorSystem, callWatchdogRef, chatName, yaml, result -> {
            IIActorRef<?> log = actorSystem.getIIActor(logActor);
            if (log == null) return;
            try {
                org.json.JSONObject args = new org.json.JSONObject();
                args.put("source", "PlanRunner");
                args.put("type", "INFO");
                args.put("data", "plan finished: " + result);
                log.callByActionName("add", args.toString());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to log plan result for " + chatName, e);
            }
        });
    }

    /**
     * Asks a conversation's plan runner to stop, if it has one. Sent with {@code tellNow} so it
     * reaches the runner's {@code stopRequested} flag without queueing behind the run it is meant
     * to stop; the runner then notices between transitions, so a plan waiting on another
     * conversation stops once that wait returns, not instantly
     * ({@code PlanRunnerLifecycleManagement_260829_oo01}).
     *
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @return {@code true} if a plan runner was found and asked to stop
     */
    public boolean stopPlan(String projectId, String chatId) {
        IIActorRef<?> plan = actorSystem.getIIActor(chatActorName(projectId, chatId) + ".plan");
        if (!(plan instanceof PlanRunnerIIAR planIIAR)) return false;
        planIIAR.tellNow(interp -> interp.requestStop());
        return true;
    }

    /**
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @return that conversation's recent log entries (oldest first), or {@code null} if it does not exist
     */
    public List<RecentEntriesAccumulator.Entry> getChatLogEntries(String projectId, String chatId) {
        RecentEntriesAccumulator buf = chatLogBuffers.get(chatActorName(projectId, chatId));
        return buf == null ? null : buf.recent();
    }

    /** @return the system-wide log's recent entries (oldest first) */
    public List<RecentEntriesAccumulator.Entry> getSystemLogEntries() {
        return systemLogBuffer.recent();
    }

    /**
     * Creates, or returns the existing, conversation {@code projectId/chat-<chatId>} as a child of
     * its owning project's {@link Project} actor (not ROOT directly), together with its {@link
     * ChatSessionIIAR} and {@link PromptQueue}.
     *
     * @param projectId owning project's id, e.g. {@code "project1"}
     * @param chatId    conversation id within that project, e.g. {@code "01"}
     * @return the conversation's actor reference
     */
    public synchronized ActorRef<ConversationTab> createChat(String projectId, String chatId) {
        String qualifiedName = chatActorName(projectId, chatId);
        ActorRef<ConversationTab> existing = chats.get(qualifiedName);
        if (existing != null) {
            return existing;
        }
        ActorRef<Project> projectRef = projects.get(projectId);
        if (projectRef == null) {
            throw new IllegalArgumentException("Unknown project: " + projectId);
        }
        ActorRef<ConversationTab> tabRef = projectRef.createChild(qualifiedName, new ConversationTab());
        chats.put(qualifiedName, tabRef);

        // Tab log multiplexer (150_TabScopedLogging_260826_oo01): this tab's own recent-entries
        // buffer, plus delegation up to the system-wide multiplexer via ForwardingAccumulator.
        String tabLogActorName = tabRef.getName() + ".log";
        RecentEntriesAccumulator tabLogBuffer = new RecentEntriesAccumulator(TAB_LOG_CAPACITY);
        chatLogBuffers.put(qualifiedName, tabLogBuffer);
        MultiplexerAccumulator tabMux = new MultiplexerAccumulator();
        tabMux.addTarget(tabLogBuffer);
        tabMux.addTarget(new ForwardingAccumulator(actorSystem, SYSTEM_LOG_ACTOR, qualifiedName));
        actorSystem.addIIActor(new MultiplexerAccumulatorActor(tabLogActorName, tabMux, actorSystem));

        // ChatSessionIIAR — manual IIActorRef bridge, since ConversationTab is a plain POJO and
        // cannot call addChildActor itself (ChatSessionIIAR_260810_oo01 "ConversationTab への接続").
        OpenAiCompatProvider provider = new OpenAiCompatProvider(servers, defaultModel);
        ChatSessionIIAR chatSessionIIAR = new ChatSessionIIAR(
                tabRef.getName() + ".chat", provider, Optional.empty(), ioLogStore, actorSystem);
        chatSessionIIAR.setParentName(tabRef.getName());
        tabRef.getNamesOfChildren().add(chatSessionIIAR.getName());
        actorSystem.addIIActor(chatSessionIIAR);
        chatSessions.put(qualifiedName, chatSessionIIAR);
        chatSessionIIAR.tell(a -> ((ChatSession) a).setChatIdentity(projectId, chatId));
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
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @return the conversation's {@link PromptQueue}, or {@code null} if it does not exist
     */
    public ActorRef<PromptQueue> getPromptQueue(String projectId, String chatId) {
        return actorSystem.getActor(chatActorName(projectId, chatId) + ".queue");
    }

    /**
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @return the conversation's {@link SseConnection}, or {@code null} if it does not exist
     */
    public ActorRef<SseConnection> getSseConnection(String projectId, String chatId) {
        return actorSystem.getActor(chatActorName(projectId, chatId) + ".sse");
    }

    /**
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @return the conversation's {@link ChatSessionIIAR}, or {@code null} if none was created
     */
    public ChatSessionIIAR getChatSession(String projectId, String chatId) {
        return chatSessions.get(chatActorName(projectId, chatId));
    }

    /**
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @return the conversation's {@link ConversationTab}, or {@code null} if none was created
     */
    public ActorRef<ConversationTab> getChat(String projectId, String chatId) {
        return chats.get(chatActorName(projectId, chatId));
    }

    /**
     * Returns the qualified names ({@code project1/chat-01}) of all conversations created so far
     * (insertion order not guaranteed — callers that need a stable display order should sort).
     *
     * @return qualified conversation names
     */
    public List<String> getChatNames() {
        return new ArrayList<>(chats.keySet());
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
