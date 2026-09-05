package com.scivicslab.chatui.core.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.RunPlanTool;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.logging.ForwardingAccumulator;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;
import com.scivicslab.chatui.openaicompat.OpenAiCompatProvider;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.plugins.logoutput.MultiplexerAccumulator;
import com.scivicslab.turingworkflow.plugins.logoutput.MultiplexerAccumulatorActor;
import com.scivicslab.turingworkflow.plugins.logoutput.MultiplexerLogHandler;
import com.scivicslab.turingworkflow.plugins.promptbuilder.PromptBuilderActor;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.RootIIAR;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
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

    // Optional, not a defaultValue: an unset property and an empty one must both mean "use the
    // built-in default" (see the Quarkus empty-string trap). Adding a root here grants whoever can
    // write files there the power to change how every conversation behaves, so the list is a
    // deliberate configuration act rather than something discovered at runtime
    // (SkillAndAgentsFile_260830_oo01 "スキル置き場を足すことは権限を与えることである").
    @ConfigProperty(name = "chat-ui.skill-roots")
    Optional<List<String>> skillRoots = Optional.empty();

    // The range of the file system a conversation's read/write may touch
    // (FileAccessScope_260830_oo01). Unset means what this system did before the range became
    // configurable: both confined to the directory the process was started in.
    @ConfigProperty(name = "chat-ui.write-root")
    Optional<String> writeRoot = Optional.empty();

    @ConfigProperty(name = "chat-ui.read-roots")
    Optional<List<String>> readRoots = Optional.empty();

    // Ceiling on what a workflow may ask to keep of one tool observation
    // (TurnResourceLimits_260830_oo01). Derived from the convention that this system's models have
    // at least a 128K-token context: 128K tokens is 256K characters at ContextBudget's
    // conservative 2.0 chars/token, half of that is a turn's share, and a turn holds at most its
    // own step limit's worth of raw observations because finished turns are collapsed to their
    // question and answer.
    @ConfigProperty(name = "chat-ui.max-observation-chars", defaultValue = "20000")
    int maxObservationChars = 20000;

    @Inject
    IoLogStore ioLogStore;

    @Inject
    IoLogView ioLogView;

    /**
     * How many recorded turns a restarted conversation gets back. The pane's own limit
     * ({@code ChatSession.MAX_HISTORY}) and the provider's token budget both still apply on top,
     * so this only bounds how much is read from the DB.
     */
    private static final int RESTORED_TURNS = 50;

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

    /** Name of the branch holding the actors that exist once regardless of the work. */
    public static final String HOUSEKEEPER = "housekeeper";

    private ActorRef<Housekeeper> housekeeperRef;
    private ActorRef<CallWatchdog> callWatchdogRef;
    private ActorRef<CollaborationGraph> collaborationGraphRef;
    private ActorRef<SkillRegistry> skillRegistryRef;
    private FileAccessScope fileScope;

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
     * The name of one conversation's ChatSession bridge.
     *
     * <p>The ChatSession is a child of the ConversationTab, not the tab itself, so its name is the
     * conversation's with {@code .chat} on the end. Anything registered under the ChatSession — the
     * provider, the prompt builder — is named from this, not from the conversation.</p>
     *
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @return the ChatSession actor's name
     */
    public static String chatSessionActorName(String projectId, String chatId) {
        return chatActorName(projectId, chatId) + CHAT_SESSION_SUFFIX;
    }

    /** What a ChatSession's name adds to its conversation's. */
    static final String CHAT_SESSION_SUFFIX = ".chat";

    /** What a provider's name adds to its ChatSession's. */
    static final String PROVIDER_SUFFIX = ".provider";

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
        // Housekeeper: the actors that exist once regardless of what work is being done. They are
        // grouped under one branch so the Actors pane shows the shape of the work, not the
        // machinery around it (NestedConversationTree_260830_oo01). Grouping changes only the tree
        // edge — the registry stays flat, so MultiplexerLogHandler's hardcoded "outputMultiplexer"
        // lookup and every getIIActor(name) call are unaffected.
        housekeeperRef = actorSystem.getRoot().createChild(HOUSEKEEPER, new Housekeeper());

        systemLogBuffer = new RecentEntriesAccumulator(SYSTEM_LOG_CAPACITY);
        MultiplexerAccumulator systemMux = new MultiplexerAccumulator();
        systemMux.addTarget(systemLogBuffer);
        MultiplexerAccumulatorActor systemLogActor =
                new MultiplexerAccumulatorActor(SYSTEM_LOG_ACTOR, systemMux, actorSystem);
        adopt(HOUSEKEEPER, systemLogActor);
        actorSystem.addIIActor(systemLogActor);
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
        callWatchdogRef = housekeeperRef.createChild("callWatchdog", new CallWatchdog());

        // Graph-engineering role assignments (CollaborationGraph_260828_oo01): likewise one for the
        // whole system — split per project, a set_collaborator naming another project's tab would be
        // written to one graph and read from another, silently losing the assignment.
        collaborationGraphRef = housekeeperRef.createChild("collaborationGraph", new CollaborationGraph());

        // Skill catalog (SkillAndAgentsFile_260830_oo01): likewise one for the whole system. A skill
        // is instructions for a kind of work, not for a project, so which conversation will want one
        // cannot be known in advance — every conversation carries the same catalog.
        List<Path> skillRootPaths = resolveSkillRoots();
        fileScope = resolveFileScope(skillRootPaths);
        LOG.info("File access: write root " + fileScope.writeRoot()
                + ", readable " + fileScope.describeReadRoots());
        SkillRegistry registry = new SkillRegistry(skillRootPaths);
        skillRegistryRef = housekeeperRef.createChild("skillRegistry", registry);
        LOG.info("Skill registry indexed " + registry.getSkills().size() + " skill(s) from "
                + registry.getRoots());
        for (String problem : registry.getProblems()) {
            LOG.warning("Skill ignored: " + problem);
        }

        // Default (first) project: only chat-01 exists at startup — chat-02, chat-03, ... are
        // created lazily as work actually needs them (ProjectScopedActorTree_260829_oo01 "開始状態
        // が違う"), not pre-seeded.
        projects.put(DEFAULT_PROJECT_ID,
                actorSystem.getRoot().createChild(DEFAULT_PROJECT_ID, new Project()));
        createChat(DEFAULT_PROJECT_ID, "01");
        reopenRecordedTabs();
        LOG.info("Actor system initialised with " + projects.size() + " project(s), "
                + chats.size() + " conversation(s)");
    }

    /**
     * Re-opens every project and conversation the I/O log still holds an unfinished session for.
     *
     * <p>The actor tree lives only in memory. Without this, a restart came up with one project and
     * one conversation however many were open when it stopped, and the rest existed only as rows
     * in the database. They were not gone — pressing "+" generated the next project name, which
     * happened to collide with a recorded one, and that conversation reappeared — but reappearing
     * by collision is not the same as being restored: with several projects the generated name
     * lands on whichever one it lands on, and a conversation nobody guesses the name of stays
     * invisible. What was open before the restart is what should be open after it.</p>
     *
     * <p>{@link #createChat} restores each conversation's own contents, so this only has to make
     * the tabs exist. The project counter is moved past every recorded name, so a later "+" cannot
     * hand out a name that is already in use.</p>
     */
    private void reopenRecordedTabs() {
        if (ioLogStore == null) return;
        List<String> tabs;
        try {
            tabs = ioLogStore.resumableTabs();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not list the recorded conversation tabs", e);
            return;
        }
        // Oldest first, so projects and conversations come back in the order they were created.
        List<String> ordered = new ArrayList<>(tabs);
        java.util.Collections.reverse(ordered);
        int reopened = 0;
        for (String tabId : ordered) {
            String[] parts = splitTabId(tabId);
            if (parts == null) continue;
            String projectId = parts[0];
            String chatId = parts[1];
            try {
                projects.computeIfAbsent(projectId,
                        id -> actorSystem.getRoot().createChild(id, new Project()));
                advanceProjectCounterPast(projectId);
                if (chats.containsKey(chatActorName(projectId, chatId))) continue;
                createChat(projectId, chatId);
                reopened++;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Could not re-open conversation " + tabId, e);
            }
        }
        if (reopened > 0) LOG.info("Re-opened " + reopened + " recorded conversation(s)");
    }

    /**
     * Splits a recorded tab id back into the project and conversation it names — the inverse of
     * {@link #chatActorName}.
     *
     * @param tabId e.g. {@code "project2/chat-01"}
     * @return {@code {projectId, chatId}}, or {@code null} when the name is not one
     *         {@link #chatActorName} produced
     */
    static String[] splitTabId(String tabId) {
        if (tabId == null) return null;
        int at = tabId.indexOf("/chat-");
        if (at <= 0) return null;
        String chatId = tabId.substring(at + "/chat-".length());
        if (chatId.isBlank()) return null;
        return new String[] {tabId.substring(0, at), chatId};
    }

    /** Keeps {@code createProject} from handing out a name a recorded project already holds. */
    private void advanceProjectCounterPast(String projectId) {
        if (!projectId.startsWith("project")) return;
        int number;
        try {
            number = Integer.parseInt(projectId.substring("project".length()));
        } catch (NumberFormatException e) {
            return;                                     // a name that was not generated by counting
        }
        nextProjectNumber.updateAndGet(next -> Math.max(next, number + 1));
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
     * Asks a conversation to abandon the LLM call it is running, if any.
     *
     * <p>Sent with {@code tellNow} so it reaches {@link ChatSession#cancel()} without queueing
     * behind the very turn it is meant to stop. An agent-loop step blocks its thread on
     * {@code providerRef.ask(...).get()} until the model's reply ends, so a queued cancel would
     * arrive only after the call it was supposed to interrupt had already finished.</p>
     *
     * <p>What the model is producing when the cancel arrives does not matter. A thinking model
     * streams its reasoning as {@code delta.reasoning_content}, so bytes keep arriving during a
     * long deliberation, and the client's read loop tests its own interrupt flag once per streamed
     * line ({@code OpenAiCompatClient.sendPrompt}). The stop therefore lands mid-thought, which is
     * the case it exists for.</p>
     *
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @return {@code true} if the conversation exists and was asked to cancel
     */
    public boolean cancelPrompt(String projectId, String chatId) {
        ChatSessionIIAR chat = chatSessions.get(chatActorName(projectId, chatId));
        if (chat == null) return false;
        chat.tellNow(a -> ((ChatSession) a).cancel());
        return true;
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
        return createChat(projectId, chatId, null);
    }

    /**
     * Creates the conversation under another conversation instead of directly under its project
     * ({@code NestedConversationTree_260830_oo01}).
     *
     * <p>Who a conversation is addressed as does not change — the registry is flat and every
     * lookup is by name. What changes is the set operations: an actor can act on all of its own
     * descendants at once ({@code apply} over {@code ./*}), and the Actors pane shows which
     * conversation is working for which.</p>
     *
     * @param projectId    owning project's id
     * @param chatId       conversation id within that project
     * @param parentChatId the conversation to place this one under, or {@code null} for the project
     * @return the conversation's actor reference
     */
    public synchronized ActorRef<ConversationTab> createChat(String projectId, String chatId,
                                                              String parentChatId) {
        String qualifiedName = chatActorName(projectId, chatId);
        ActorRef<ConversationTab> existing = chats.get(qualifiedName);
        if (existing != null) {
            return existing;
        }
        ActorRef<Project> projectRef = projects.get(projectId);
        if (projectRef == null) {
            throw new IllegalArgumentException("Unknown project: " + projectId);
        }
        ActorRef<?> parentRef = projectRef;
        if (parentChatId != null && !parentChatId.isBlank()) {
            ActorRef<ConversationTab> parentChat =
                    chats.get(resolveChatName(projectId, parentChatId));
            if (parentChat == null) {
                throw new IllegalArgumentException("Unknown parent conversation: " + parentChatId);
            }
            parentRef = parentChat;
        }
        ActorRef<ConversationTab> tabRef = parentRef.createChild(qualifiedName, new ConversationTab());
        chats.put(qualifiedName, tabRef);

        // Tab log multiplexer (150_TabScopedLogging_260826_oo01): this tab's own recent-entries
        // buffer, plus delegation up to the system-wide multiplexer via ForwardingAccumulator.
        String tabLogActorName = tabRef.getName() + ".log";
        RecentEntriesAccumulator tabLogBuffer = new RecentEntriesAccumulator(TAB_LOG_CAPACITY);
        chatLogBuffers.put(qualifiedName, tabLogBuffer);
        MultiplexerAccumulator tabMux = new MultiplexerAccumulator();
        tabMux.addTarget(tabLogBuffer);
        tabMux.addTarget(new ForwardingAccumulator(actorSystem, SYSTEM_LOG_ACTOR, qualifiedName));
        MultiplexerAccumulatorActor tabLogActor =
                new MultiplexerAccumulatorActor(tabLogActorName, tabMux, actorSystem);
        adopt(tabRef.getName(), tabLogActor);
        actorSystem.addIIActor(tabLogActor);

        // ChatSessionIIAR — manual IIActorRef bridge, since ConversationTab is a plain POJO and
        // cannot call addChildActor itself (ChatSessionIIAR_260810_oo01 "ConversationTab への接続").
        OpenAiCompatProvider provider = new OpenAiCompatProvider(servers, defaultModel);
        ChatSessionIIAR chatSessionIIAR = new ChatSessionIIAR(
                tabRef.getName() + CHAT_SESSION_SUFFIX, provider, Optional.empty(), ioLogStore,
                actorSystem);
        chatSessionIIAR.setParentName(tabRef.getName());
        tabRef.getNamesOfChildren().add(chatSessionIIAR.getName());
        actorSystem.addIIActor(chatSessionIIAR);
        chatSessions.put(qualifiedName, chatSessionIIAR);
        chatSessionIIAR.tell(a -> ((ChatSession) a).setChatIdentity(projectId, chatId));
        chatSessionIIAR.tell(a -> ((ChatSession) a).setWatchdogRef(callWatchdogRef));
        chatSessionIIAR.tell(a -> ((ChatSession) a).setCollaborationGraphRef(collaborationGraphRef));
        chatSessionIIAR.tell(a -> ((ChatSession) a).setSkillRegistryRef(skillRegistryRef));
        chatSessionIIAR.tell(a -> ((ChatSession) a).setFileScope(fileScope));
        chatSessionIIAR.tell(a -> ((ChatSession) a).setMaxObservationChars(maxObservationChars));
        // A conversation created after its project's working directory was set must still receive
        // that project's instructions, so they are pulled from the Project actor here rather than
        // pushed only at the moment setProjectWorkingDir runs.
        String projectInstructions = readProjectInstructions(projectRef);
        chatSessionIIAR.tell(a -> ((ChatSession) a).setProjectInstructions(projectInstructions));

        // provider child — created by the generating side, not by ChatSession itself
        // (ChatSessionPorting_260823_oo01 "なぜ init を無くしたか").
        LlmProvider providerAsLlmProvider = provider;
        ActorRef<LlmProvider> providerRef = chatSessionIIAR.<LlmProvider>createChild(
                chatSessionIIAR.getName() + PROVIDER_SUFFIX, providerAsLlmProvider);
        chatSessionIIAR.tell(a -> ((ChatSession) a).setProviderName(providerRef.getName()));

        // Prompt builder — a child of the ChatSession, so a prompt-construction sub-workflow (also
        // registered as a child of the ChatSession by Interpreter.call) reaches it as its own
        // sibling and never touches another conversation's buffer
        // (DocRetrievalAgentLoop_260830_oo01).
        PromptBuilderActor promptBuilder =
                new PromptBuilderActor(chatSessionIIAR.getName() + ".promptBuilder", actorSystem);
        promptBuilder.setParentName(chatSessionIIAR.getName());
        chatSessionIIAR.getNamesOfChildren().add(promptBuilder.getName());
        actorSystem.addIIActor(promptBuilder);

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

        restoreConversation(qualifiedName, chatSessionIIAR, providerRef);

        return tabRef;
    }

    /**
     * Refills a just-created conversation from what the I/O log recorded for it, so that restarting
     * the process does not empty the pane or lose what the model was told
     * ({@code ConversationRestoreOnRestart_260904_oo01}).
     *
     * <p>Two lists are rebuilt, because they are different things. The {@code ChatSession}'s own
     * conversation is what {@code GET /conversation} returns and the browser draws. The provider's
     * history is what accompanies the next LLM call. Filling only the first would show the
     * conversation without the model remembering it; filling only the second would do the
     * reverse.</p>
     *
     * <p>Nothing happens when the tab has no resumable session, which is the case for a genuinely
     * new conversation and for one the user cleared before the restart.</p>
     *
     * @param tabName         the conversation's qualified actor name, which is also its log tab id
     * @param chatSessionIIAR the conversation's ChatSession bridge
     * @param providerRef     the conversation's own provider
     */
    private void restoreConversation(String tabName, ChatSessionIIAR chatSessionIIAR,
                                     ActorRef<LlmProvider> providerRef) {
        if (ioLogStore == null || ioLogView == null) return;
        long sessionId = ioLogStore.findResumableSession(tabName);
        if (sessionId < 0) return;

        List<IoLogView.Turn> turns;
        int lastTurn;
        try {
            turns = ioLogView.conversation(sessionId, RESTORED_TURNS);
            lastTurn = ioLogView.lastTurnNumber(sessionId);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read the recorded conversation for " + tabName, e);
            return;
        }

        // Done whether or not anything is restored: a session recorded before this feature existed
        // holds turns but no conversation entry, and its numbering must still be continued.
        if (lastTurn > 0) {
            chatSessionIIAR.tell(a -> ((ChatSession) a).resumeTurnNumbering(lastTurn));
        }

        if (turns.isEmpty()) {
            LOG.info("I/O log session " + sessionId + " holds no restorable turn for " + tabName
                    + "; continuing it from turn " + (lastTurn + 1));
            return;
        }

        for (IoLogView.Turn t : turns) {
            chatSessionIIAR.tell(a -> {
                ChatSession c = (ChatSession) a;
                c.recordHistory("user", t.question());
                c.recordHistory("assistant", t.answer());
            });
            // collapseTurn appends the pair and moves the collapsed/running boundary past it, which
            // is the same state a turn ending normally leaves behind — so seeding is just replaying
            // it once per restored turn, and needs no separate provider API.
            providerRef.tell(p -> p.collapseTurn(t.question(), t.answer()));
        }
        LOG.info("Restored " + turns.size() + " turn(s) into " + tabName
                + " from I/O log session " + sessionId);
    }

    /**
     * Puts an actor that registers itself ({@code addIIActor}) into the tree under {@code parent}.
     * {@code createChild} does this for actors it creates; one that arrives ready-made needs both
     * halves — its own parent name, and its entry in the parent's child list — or it shows up at
     * the top of the Actors pane with no owner.
     *
     * @param parentName the owning actor's registry name
     * @param child      the actor to place under it
     */
    private void adopt(String parentName, IIActorRef<?> child) {
        child.setParentName(parentName);
        ActorRef<?> parent = actorSystem.getActor(parentName);
        if (parent == null) parent = actorSystem.getIIActor(parentName);
        if (parent != null) parent.getNamesOfChildren().add(child.getName());
    }

    /** @return the configured skill roots, or {@code ~/.claude/skills} when none is configured */
    private List<Path> resolveSkillRoots() {
        List<String> configured = skillRoots.orElse(List.of()).stream()
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (configured.isEmpty()) {
            return List.of(Path.of(System.getProperty("user.home"), ".claude", "skills"));
        }
        return configured.stream().map(Path::of).map(Path::toAbsolutePath).toList();
    }

    /**
     * Builds the file range from configuration. A skill root is readable without being listed
     * again: {@code load_skill}'s third loading level is the {@code read} tool, so configuring a
     * skill root already declares the intent to read what is in it
     * ({@code FileAccessScope_260830_oo01}).
     *
     * @param skillRootPaths the configured skill roots
     * @return the range every conversation gets
     */
    private FileAccessScope resolveFileScope(List<Path> skillRootPaths) {
        Path write = writeRoot.map(String::strip).filter(v -> !v.isEmpty())
                .map(Path::of).orElseGet(() -> Path.of("").toAbsolutePath());
        List<Path> extra = new ArrayList<>(skillRootPaths);
        for (String configured : readRoots.orElse(List.of())) {
            String stripped = configured.strip();
            if (!stripped.isEmpty()) extra.add(Path.of(stripped));
        }
        return new FileAccessScope(write, extra);
    }

    /** @return the range every conversation's read/write is confined to */
    public FileAccessScope getFileScope() {
        return fileScope;
    }

    /** @return the given project's instructions, or {@code null} if it has none */
    private String readProjectInstructions(ActorRef<Project> projectRef) {
        try {
            return projectRef.ask(Project::getInstructions).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to read project instructions", e);
            return null;
        }
    }

    /**
     * Points a project at a working directory, reads that directory's {@code AGENTS.md} (or
     * {@code CLAUDE.md}), and hands the result to every conversation already in that project
     * ({@code SkillAndAgentsFile_260830_oo01}).
     *
     * @param projectId  the project to point
     * @param workingDir the directory, or {@code null} to clear it
     * @return a one-line account of what was loaded, or an {@code error: ...} string
     */
    public String setProjectWorkingDir(String projectId, Path workingDir) {
        ActorRef<Project> projectRef = projects.get(projectId);
        if (projectRef == null) return "error: unknown project: " + projectId;
        String outcome;
        try {
            outcome = projectRef.ask(p -> p.setWorkingDir(workingDir)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to set working directory for " + projectId, e);
            return "error: " + e.getMessage();
        }
        String instructions = readProjectInstructions(projectRef);
        String prefix = projectId + "/";
        for (Map.Entry<String, ChatSessionIIAR> entry : chatSessions.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            entry.getValue().tell(a -> ((ChatSession) a).setProjectInstructions(instructions));
        }
        return outcome;
    }

    /**
     * @param projectId the project to describe
     * @return that project's {@link Project} actor, or {@code null} if there is no such project
     */
    public ActorRef<Project> getProject(String projectId) {
        return projects.get(projectId);
    }

    /** @return the shared {@link SkillRegistry}, or {@code null} before the system is initialised */
    public ActorRef<SkillRegistry> getSkillRegistry() {
        return skillRegistryRef;
    }

    /**
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @return the conversation's {@link PromptQueue}, or {@code null} if it does not exist
     */
    /**
     * A conversation's own LLM provider, by name.
     *
     * <p>Looked up rather than held: the provider is a child of the ChatSession, created when the
     * conversation is ({@code ChatSessionPorting_260823_oo01}). A child of the ChatSession, which
     * is itself a child of the conversation — so the name carries both steps, and asking for
     * {@code <conversation>.provider} finds nothing.</p>
     *
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @return that conversation's provider, or {@code null} if it has none
     */
    @SuppressWarnings("unchecked")
    public ActorRef<LlmProvider> getProviderRef(String projectId, String chatId) {
        return (ActorRef<LlmProvider>) (ActorRef<?>)
                actorSystem.getActor(chatSessionActorName(projectId, chatId) + PROVIDER_SUFFIX);
    }

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
            return new ActorNode("chat-ui", "IIActorSystem", null, false, List.of());
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
        return new ActorNode(name, type, ActorNotes.noteOf(name), ref.isAlive(), children);
    }
}
