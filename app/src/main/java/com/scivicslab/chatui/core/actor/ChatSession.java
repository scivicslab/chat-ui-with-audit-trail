package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.agent.ContextBudget;
import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ChatInspection;
import com.scivicslab.chatui.agent.TextToolCallParser;
import com.scivicslab.chatui.agent.ToolCall;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.plugin.ConversationTool;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.core.service.AuthMode;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POJO owning the entire state of one ConversationSquad's chat session.
 *
 * <p>Extends {@link Interpreter} so that, once it loads its own agent-loop workflow
 * (see {@code ChatSessionAgentLoop_260823_oo01}), it can drive a multi-turn tool-calling
 * loop through the same state-machine mechanism a Turing workflow uses for any sub-workflow.
 * This class (stage 1) does not yet load or run such a workflow — it only ports the
 * single-turn prompt/response lifecycle ({@code ChatSessionPorting_260823_oo01}'s 2-a/2-c).</p>
 *
 * <p>All fields are plain (no volatile / synchronized) — thread safety is guaranteed
 * by the actor's sequential message processing once this object is wrapped by
 * {@code ChatSessionIIAR}.</p>
 *
 * <p>Unlike the reference {@code ChatActor} this is ported from, this class holds no
 * {@code ActorRef}-typed fields for its collaborators (provider/watchdog/promptQueue).
 * It holds only their names and looks them up via {@code system.getActor(name)} at the
 * point of use — see {@code ChatSessionPorting_260823_oo01} "How to do it".</p>
 */
public class ChatSession extends Interpreter {

    private static final Logger logger = Logger.getLogger(ChatSession.class.getName());
    private static final int MAX_HISTORY = 200;
    private static final int LOG_BUFFER_SIZE = 500;

    /** The conversation's provider. Not final: setProvider replaces it when the conversation's
 *  provider kind changes (CliHarnessProvider_260912_oo01). */
    // volatile: isCommand() is read from a request thread while a turn occupies this actor's thread.
    private volatile LlmProvider provider;
    private AuthMode authMode;

    /** Complete I/O log (Sessions tab). May be null (logging disabled / not yet ported). */
    private final IoLogStore ioLog;
    /** Conversation turn counter, used to label I/O-log entries ({@code turn<n>/step1/llm}). */
    private int ioTurn = 0;

    /** Name of the {@code provider} child actor. Created and set once by the generating side. */
    private String providerName;
    /**
     * Name of the sibling StallMonitor, or {@code null}. Always null for the openai-compat
     * provider this class currently targets — StallMonitor is not yet ported
     * ({@code ChatSessionPorting_260823_oo01} Under the Hood).
     */
    private String watchdogName;
    /** Name of the sibling PromptQueue, or {@code null} until wired. */
    private String promptQueueName;
    /** This session's owning project id (e.g. {@code "project1"}) — see {@code Terminology_260829_oo01}. */
    private String projectId;
    /** This session's conversation id within its project (e.g. {@code "01"}), used to key its I/O-log session. */
    private String chatId;
    /** The shared {@link CallWatchdog}, consulted by the {@code ask_chat} tool before it waits on
     *  another ConversationSquad — distinct from {@link #watchdogName} (an unrelated, unported StallMonitor field). */
    private ActorRef<CallWatchdog> watchdogRef;
    /** This conversation's project's {@link RoleTable}, consulted (and updated) by the {@code set_collaborator}
     *  tool and by babysitter-loop methods resolving a role (e.g. {@code "worker"}) to a chat id. */
    private ActorRef<RoleTable> roleTableRef;
    private ConversationControls conversationControls;
    /** The shared {@link SkillRegistry}, backing the {@code load_skill} tool and the skill catalog
     *  carried in this session's system prompt ({@code SkillAndAgentsFile_260830_oo01}). */
    private ActorRef<SkillRegistry> skillRegistryRef;
    /** This session's project's {@code AGENTS.md} (or {@code CLAUDE.md}) text, or {@code null} when
     *  the project has no working directory or that directory holds neither file. */
    private String projectInstructions;

    // volatile: the only writer is this actor's own thread (PromptQueue.tryDispatch runs via
    // chatSessionRef.tell(...)), but BusyStateReadableSnapshot_260828_oo01's isBusyDirect() reads it
    // from any thread — safe publication of a single-writer/multi-reader flag, not a loosening of
    // the actor-thread-exclusivity rule (writes stay exactly where they always were).
    private volatile boolean busy;

    // ---- Stalled-turn recovery (StalledTurnRecovery, tasks/stalled-turn-recovery.md) ----
    /** When the model call in flight began, in milliseconds since the epoch, or 0 when no call is in flight. */
    private volatile long llmCallStartedAt;
    /** Whether the model call in flight has produced anything at all: a streamed event or any line of the provider. */
    private volatile boolean llmCallProducedOutput;
    /** Set by {@link #stopForRecovery}: the running turn ends without answering, and its prompt goes back to the queue. */
    private volatile boolean recoverRequested;
    /** Set by {@link #abandonCallForRecovery}: the step stops waiting for a model call that did not end when cancelled. */
    private volatile boolean abandonCall;
    /** The usage-limit refusal the running turn got ({@link ChatEvent#usageLimit}), or {@code null}. */
    private volatile String usageLimitMessage;
    /** When the queue was held because a turn got the usage-limit refusal, or 0 when it is not. */
    private volatile long limitHeldSince;
    private String apiKey;
    private final LinkedList<HistoryEntry> conversationHistory = new LinkedList<>();
    // Same safe-publication pattern as `busy`, for the same reason (BusyStateReadableSnapshot_260828_oo01):
    // written only by recordHistory()/history-clear, both always on this actor's own thread; read from
    // any thread via getHistorySnapshotDirect() without going through the actor's mailbox.
    private final java.util.concurrent.atomic.AtomicReference<List<HistoryEntry>> historySnapshot =
            new java.util.concurrent.atomic.AtomicReference<>(List.of());

    private final ChatEvent[] logBuffer = new ChatEvent[LOG_BUFFER_SIZE];
    private int logHead = 0;
    private int logCount = 0;
    private Consumer<ChatEvent> sseEmitter;

    // ---- MCP result accumulation ----
    // Keyed by UUID assigned at submitPrompt time. LRU-evicts oldest when >50 entries.
    private final Map<String, String> completedResults = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 50;
        }
    };
    // How each stored result's turn ended, keyed like completedResults, for the line ask_chat adds
    // to a reply. Same 50-entry bound.
    private final Map<String, String> completedEndings = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 50;
        }
    };
    /** Tools this turn has run, for the ending line. Reset in {@code start}. */
    private int turnToolCalls;
    /** Whether this turn stopped at the workflow's step limit. Reset in {@code start}. */
    private boolean stepLimitHit;
    /**
     * The question the latest step put to the person with {@code ask_person}, or {@code null}. Set by
     * {@code stepExpectingAction}, read by {@code askedThePerson}; once set, the turn ends
     * ({@code AskThePersonEndsTheTurn_261119_oo01}). Reset in {@code start}.
     */
    private String personQuestion;
    /** Where this turn's prompt came from, as {@code PromptQueue} gave it. Set in {@code start}. */
    private String turnSource;
    // UUIDs that have been registered (via submitPrompt) but not yet started processing
    private final Set<String> pendingResultKeys = new HashSet<>();
    // UUID of the prompt currently being processed, or null
    private String activeResultKey;

    // ---- Agent loop (ChatSessionAgentLoop_260823_oo01): start -> (stepExpectingAction -> runTool)* -> finish ----
    /**
     * Steps allowed when the workflow's step-limit transition names no number
     * ({@code TurnResourceLimits_260830_oo01}). Not the limit itself: the limit is what the
     * workflow says. This value exists so that an agent loop installed by {@code set_agent_loop} that
     * forgot its step-limit transition still stops, instead of running to
     * {@code Interpreter.runUntilEnd}'s 10000-iteration backstop.
     */
    private static final int DEFAULT_STEP_LIMIT = 30;
    /** How many consecutive cut-off replies a turn continues through ({@code think-cut-off}). */
    static final int DEFAULT_MAX_CUT_OFFS_IN_A_ROW = 3;
    /** How many broken tool calls in a row a turn repairs when the agent loop names no number. */
    static final int DEFAULT_MAX_REPAIRS_IN_A_ROW = 3;
    /** When the running turn began, for the duration reported with its answer. */
    private volatile long turnStartedAt;
    /** Ceiling on the observation size a workflow may ask for; set from configuration. */
    private int maxObservationChars = ContextBudget.OBS_THRESHOLD;
    /**
     * The range of the file system this conversation's {@code read}/{@code write} may touch. The
     * default keeps both confined to the directory the process was started in; the generating side
     * replaces it with the configured range ({@code FileAccessScope_260830_oo01}).
     */
    private FileAccessScope fileScope = FileAccessScope.processDirectory();

    /** The tool a conversation calls to ask the person and end its turn ({@code AskThePersonEndsTheTurn_261119_oo01}). */
    public static final String ASK_PERSON = "ask_person";

    /** How asking the person ends the turn; said to every model, however it calls tools. */
    private static final String ASK_PERSON_NOTE = """
            Asking the person ends your turn. When you need the person's answer or decision before you go \
            on — choosing between options you offer, approving a plan, confirming something you are unsure \
            of — call ask_person with the question, and put no other tool call in that reply. A reply that \
            asks a question or waits for a decision must not also start the work it asks about: anything \
            else it calls is not run, and the turn ends. The answer arrives as your next message.""";

    /** How to show an image; said to every model, however it calls tools. */
    private static final String IMAGE_NOTE = """
            To show an image (a diagram, an icon, a drawing you were asked for), write a fenced code \
            block whose language is "svg", containing valid, self-contained SVG markup — it renders as \
            an actual picture in the chat pane. This is the only way to produce an image here: there is \
            no image-generation tool or model, so do not call one or invent one. Do not wrap the SVG in \
            a data: URI or a markdown image link; write the <svg>...</svg> markup itself.""";

    private static final String SYSTEM_PROMPT_HEAD = """
            You are a helpful assistant with access to tools. To call a tool, write EXACTLY this format \
            in your reply (nothing else on those lines). Every parameter, without exception, uses a \
            <parameter name="..."> tag — never a bare tag named after the parameter (e.g. write "<parameter \
            name="path">..." NOT "<path>...").

            One-parameter example:
            <invoke name="calc">
            <parameter name="expression">23*47</parameter>
            <reason>one concise sentence on why you need this now</reason>
            </invoke>

            Two-parameter example (write always needs both path AND content, each its own <parameter> tag):
            <invoke name="write">
            <parameter name="path">notes.txt</parameter>
            <parameter name="content">the text to save</parameter>
            <reason>one concise sentence on why you need this now</reason>
            </invoke>

            When an answer shows a call block as an example, write its angle brackets as square \
            brackets, e.g. [invoke name="calc"] ... [/invoke], so that it is not run.

            """ + ASK_PERSON_NOTE + """

            Available tools:
            """;

    /**
     * The harness's notes for a model given its tools as definitions ({@code OpenAiCompatStandardToolCalling_261120_oo01}):
     * what {@link #SYSTEM_PROMPT_HEAD} and {@link #SYSTEM_PROMPT_TAIL} say, less how to write a call and the list of
     * tools, which the request's tool definitions carry.
     */
    private static final String HARNESS_NOTES = """
            You are a helpful assistant with tools. Call a tool whenever you need what it gives; after its \
            result comes back, call another tool or give your final answer. When you have enough \
            information, answer in plain text without calling a tool: that text is taken as your final \
            answer to the user.

            """ + ASK_PERSON_NOTE + "\n\n" + IMAGE_NOTE;

    /**
     * What the first step's prompt says about each tool, keyed by tool name, in the order they are
     * listed. Which of them a conversation is shown — and may call — is its {@link ToolSet}
     * ({@code CliHarnessProvider_260912_oo01}).
     */
    private static final Map<String, String> TOOL_DESCRIPTIONS = invokeDescriptions();

    /**
     * The arguments a tool runs without: the ones its description calls optional, and the ones its code
     * gives a meaning when left out (accept_reply's requestId is the reply the turn began with,
     * set_chat_name's name clears the name, expect_report's chatId is this conversation, set_superior's
     * superiorChatId removes the superior, write's content writes an empty file). They are the arguments
     * {@link ToolCatalog} does not mark required. Every other argument in a tool's signature is one the tool
     * cannot run without, and a call that lacks it is repaired rather than run ({@code BrokenToolCallRepair_261115_oo01}).
     */
    static final Map<String, List<String>> OPTIONAL_PARAMETERS = optionalParameters();

    /**
     * @return the arguments {@code tool} cannot run without: its signature's arguments less the optional
     *         ones; empty for a tool this conversation has no description of
     */
    static List<String> requiredParameters(String tool) {
        List<String> signature = signatureOf(tool);
        List<String> optional = OPTIONAL_PARAMETERS.getOrDefault(tool, List.of());
        return signature.stream().filter(a -> !optional.contains(a)).toList();
    }

    /**
     * @return whether {@code name} is a tool this conversation is shown and may call: a built-in or plugin tool
     *         that its {@link ToolSet} contains
     */
    private boolean toolOfThisConversation(String name) {
        return (TOOL_DESCRIPTIONS.containsKey(name) || pluginTools.containsKey(name)) && toolSet.contains(name);
    }

    /** @return the description of the built-in tool {@code tool} the model is shown, or {@code null} when there is none */
    static String descriptionOf(String tool) {
        return TOOL_DESCRIPTIONS.get(tool);
    }

    /** @return the argument names in {@code - tool(a, b)} of the tool's description, in order */
    static List<String> signatureOf(String tool) {
        ToolCatalog.Spec spec = ToolCatalog.builtIn(tool);
        return spec == null ? List.of() : spec.signature();
    }

    /** @return each built-in tool's {@code <invoke>} entry, keyed by name, in {@link ToolCatalog}'s order */
    private static Map<String, String> invokeDescriptions() {
        Map<String, String> m = new LinkedHashMap<>();
        ToolCatalog.builtInTools().forEach((name, spec) -> m.put(name, spec.invokeDescription()));
        return Collections.unmodifiableMap(m);
    }

    /** @return each built-in tool that has optional arguments, with those arguments in signature order */
    private static Map<String, List<String>> optionalParameters() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        ToolCatalog.builtInTools().forEach((name, spec) -> {
            List<String> optional = spec.signature().stream().filter(a -> !spec.required().contains(a)).toList();
            if (!optional.isEmpty()) m.put(name, optional);
        });
        return Collections.unmodifiableMap(m);
    }

    private static final String SYSTEM_PROMPT_TAIL = """
            Call at most one tool per reply. After a tool result comes back, either call another tool \
            or give your final answer. When you have enough information, answer in plain text with NO \
            <invoke> block — that plain text is taken as your final answer to the user.

            """ + IMAGE_NOTE;

    // Per-turn working memory, reset in start(). Not thread-confined by field type (see
    // ChatSessionAgentLoop_260823_oo01's own note in chat-session-agent-loop.yaml) — safe only
    // because runUntilEnd() is always invoked as a plain method from within an existing tell()
    // closure, so the whole turn runs on this actor's own single thread.
    private String question;
    private String pendingPrompt;
    // A reply cut off at the output limit (CutOffReplyContinues_261115_oo01). cutOffPending: the step
    // that just ran was cut off and continueAfterCutOff has not dealt with it yet; cutOffRun: how many
    // steps in a row were cut off; cutOffText: the text those steps wrote, kept for the answer if the
    // turn gives up; cutOffChars: how much the latest one wrote.
    private boolean cutOffPending;
    private int cutOffRun;
    private int cutOffChars;
    private final StringBuilder cutOffText = new StringBuilder();
    // A reply that tried to call a tool but whose call cannot be run as written
    // (BrokenToolCallRepair_261115_oo01). repairPending: the step that just ran was such a reply and
    // repairBrokenCall has not dealt with it yet; repairRun: how many steps in a row were; repairText:
    // the latest such reply, kept for the answer if the turn gives up; repairProblems and repairTool: what
    // was wrong, and the tool whose exact form the note shows.
    private boolean repairPending;
    private int repairRun;
    private String repairText;
    private List<String> repairProblems = List.of();
    private String repairTool;
    // Data URLs attached to this turn's prompt, sent to the provider on step 1 only
    // (stepExpectingAction) — a later step's provider call carries a tool observation, not the
    // original attachment, so re-sending them would just repeat the same image every step.
    private List<String> pendingImages = List.of();
    private String turnModel;
    private Consumer<ChatEvent> turnEmitter;
    private ActorRef<ChatSession> turnSelf;
    private CompletableFuture<TurnReply> turnDone;
    private boolean turnNoThink;
    /**
     * Whether this conversation asks its model to skip the thinking phase, whatever one prompt
     * says. A setting of the conversation, as the model and the temperature are
     * ({@code ThinkingAndEffortAreConversationSettings_260917_oo01}).
     */
    private boolean noThinkAlways;
    /**
     * Open I/O-log session id for this turn, or -1 when logging is disabled. Set in start(). Volatile, like
     * {@link #ioTurnNo} and {@link #stepCount}: chat-ui's MCP server reads all three from its own thread to label
     * the calls it records ({@link ConversationToolRunner.IoPosition}).
     */
    private volatile long ioSession = -1;
    /** Which tools this conversation is shown and may call; every conversation starts with all of them. */
    private ToolSet toolSet = ToolSet.FULL;
    /**
     * The provider's error for this turn, when a step's call failed and produced no text
     * (TurnErrorInConversation_260913_oo01). Kept so the failure reaches the history and the I/O
     * log instead of ending the turn as an empty answer.
     */
    private String turnError;
    /** Tools a plugin jar added (ProviderAndToolPlugins_260912_oo01), by name, in registration order. */
    private final Map<String, ConversationTool> pluginTools = new LinkedHashMap<>();
    /** This turn's number, for turn{N}/step{M}/... I/O-log labels. Set in start(). */
    private volatile int ioTurnNo;

    /**
     * The {@code ask_chat} request the current turn's prompt is, or answers, or {@code null}
     * ({@code TurnAndRequestIds_261107_oo01}).
     */
    private String turnRequestId;

    /** How many times the current turn has called {@code ask_chat}; numbers its request ids from 1. */
    private int askCount;

    /**
     * The requests this conversation has received and not yet answered, and the requests sent for
     * them ({@code ReportReachesTheAskerAfterDelegation_261107_oo01}).
     */
    private final OpenRequests openRequests = new OpenRequests();

    /** The original request the current turn works for, or {@code null}. */
    private String turnOriginal;

    /**
     * The message the current turn's work was asked for in: the turn's own prompt, or, for a turn that
     * resumes a paused one, the message the paused turn was working for. The resume message repeats it, so
     * the model continues that work and not another one in the history.
     */
    private String turnTask;

    /**
     * What a paused turn knew about why it was running, kept whole until the turn that resumes it starts:
     * the message it worked for, the reply it had not yet decided on, and where a reminder's answer goes
     * ({@code ReplyDecisionAndPromisedReport_261108_oo01}). Anything a turn carries for its purpose belongs
     * here, so a pause loses none of it.
     */
    private record PausedTurn(String task, String undecidedRequest, String undecidedReplier, String undecidedEnding,
                              String reminderRequest, FollowUpTo followUp) {}

    /** The paused turns, by the paused turn's id, which its resume message's source names. */
    private final Map<String, PausedTurn> pausedTurns = new LinkedHashMap<>();

    /** The current turn's answer, its ending line and its id, once {@link #finish} has it. */
    private TurnReply turnAnswer;

    /**
     * What the current turn has done so far, step by step: what the model wrote and what each tool
     * returned. Becomes the turn's answer in the provider's history if the turn is paused, so the
     * turn that resumes the work can read it ({@code TimekeeperPausesATurnForAnUrgentRequest_261107_oo01}).
     */
    private final StringBuilder turnTranscript = new StringBuilder();

    /**
     * Set by {@link #pauseForUrgent} from the timekeeper's thread while a turn runs: the turn is to
     * stop so that an urgent request can be answered, and the work is to go on afterwards.
     */
    private volatile boolean pauseRequested;

    /**
     * The child requests still out, published after every change so the timekeeper can read them
     * while a turn holds this actor's thread (as {@link #isBusy()} is read).
     */
    private volatile List<OpenRequests.Outstanding> outstandingSnapshot = List.of();
    /** The sent requests with a promised report pending, for the timekeeper to read without waiting for a turn. */
    private volatile java.util.Set<String> promisedSnapshot = java.util.Set.of();

    /** @return the sent requests whose promised report is pending, read without waiting for a running turn */
    public java.util.Set<String> promisedRequests() { return promisedSnapshot; }

    /** Source of the message that resumes a paused turn. */
    public static final String RESUME_SOURCE = "agent:resume";

    /**
     * Source of a reminder the timekeeper puts in, followed by the reminder's number, a colon, and the
     * conversation waiting for the report or {@link #REMINDER_TO_SCREEN}
     * ({@code ReplyDecisionAndPromisedReport_261108_oo01}).
     */
    public static final String REMINDER_SOURCE_PREFIX = "agent:reminder:";

    /** What a reminder's source names when the report was promised to the person. */
    public static final String REMINDER_TO_SCREEN = "screen";

    /** Who waits for the answer of a turn a reminder starts, and which reminder it was. */
    private record FollowUpTo(String asker, int number, OpenRequests.RemoteLink link) {
        FollowUpTo(String asker, int number) { this(asker, number, null); }
    }

    /** The request id of the reminder this turn answers, and who waits for its answer; {@code null} if none. */
    private String turnReminder;
    private FollowUpTo turnFollowUp;

    /**
     * The reply this turn began with that has had no decision yet: its request id, and the conversation that
     * sent it. {@code null} when the turn did not begin with a reply, or a decision was recorded
     * ({@code ReplyDecisionAndPromisedReport_261108_oo01}).
     */
    private String undecidedRequest;
    private String undecidedReplier;

    /** Whether this turn has already been asked once for a decision on its reply. */
    private boolean decisionAsked;
    /**
     * How the message this turn decides on ended, read from its ended line: {@code answered}, {@code asked},
     * {@code step-limit}, {@link #ENDED_WITHOUT_A_REPLY}, or {@code null} for a follow-up, which has no ended line
     * ({@code AutomaticReplyDecision_261119_oo01}).
     */
    private String undecidedEnding;
    /** Whether this turn's end has already settled the reply it began with, so it is settled once. */
    private boolean replyDecisionSettled;

    /** The text the model wrote before it was asked for a decision, kept for the turn's answer. */
    private String answerBeforeDecision;
    private List<ToolCall> pendingCalls;
    private String finalAnswer;
    private volatile int stepCount;
    private volatile boolean cancelled;

    // ---- Babysitter loop (BabysitterLoopWorkflowShape_260828_oo01, made phase-agnostic by
    // GenericBabysitterPhases_260829_oo01) — per-turn, reset in start() ----
    /** Redos allowed when the workflow names no number ({@code TurnResourceLimits_260830_oo01}). */
    private static final int DEFAULT_REDO_LIMIT = 3;
    /**
     * How long a babysitter phase waits for its worker. Far above {@code AskChatTool}'s 60s default,
     * because the worker runs its own multi-step agent loop (web searches, then writing) inside this
     * one call — at 60s the wait would expire before the judging logic ever ran
     * ({@code BabysitterRealisticE2eScenario_260828_oo01} "前提条件：内部タイムアウトの引き上げが必要").
     */
    private static final int WORKER_WAIT_TIMEOUT_SECONDS = 600;
    /** The worker's latest reply — search results in one phase, a draft in another; one field serves both. */
    private String lastWorkerReply;
    /** Raw text of judgeResult's one LLM call, consumed (not re-queried) by judgeNeedsRedo. */
    private String lastJudgment;
    /** What to fix, taken from lastJudgment by judgeNeedsRedo, sent by requestRedo. */
    private String redoNote;
    /** Redos used in the current phase — reset by requestFromWorker, which every phase starts with. */
    private int redoCount;
    /** Set by requestFromWorker/requestRedo on failure; consumed by reportCollaborationFailure. */
    private String lastCollaborationError;

    // ---- Prompt construction (ChatSessionPorting_260823_oo01 2-b-i) ----
    // stepExpectingAction() delegates building the text it sends to provider.sendPrompt() to a
    // swappable sub-workflow (Interpreter.call(...)), rather than constructing it directly. The
    // sub-workflow pushes one or more constructed prompts via appendConstructedPrompt(); this
    // queue is drained one prompt per stepExpectingAction() call, so a sub-workflow that splits
    // one turn's input into N prompts (e.g. N checklist items) sends them as N separate LLM
    // turns instead of one combined call.
    /**
     * One thing a prompt-construction sub-workflow asked to send.
     *
     * @param prompt              the text of the user message
     * @param requestMessagesJson the request messages to send it with, as a JSON array that does not
     *                            include the user message; {@code null} when the provider's own are to be used
     */
    private record Constructed(String prompt, String requestMessagesJson) {}

    private final Deque<Constructed> constructedPrompts = new ArrayDeque<>();

    /**
     * The provider's request messages as they were before this turn replaced them, or {@code null}
     * when this turn replaced none. Put back when the turn ends, so what a turn built for its own
     * requests (a {@code system} message, a chosen part of the past) is not kept for the next one
     * ({@code AgentLoopDecidesThePrompt_261020_oo01}).
     */
    private String requestMessagesToRestore;
    /** Classpath workflow file used to build each step's prompt(s); swappable via the setter. */
    /** The prompt-construction sub-workflow a conversation uses when its agent loop names none. */
    public static final String DEFAULT_PROMPT_WORKFLOW = "prompt-construction-default.yaml";

    private String promptWorkflowFile = DEFAULT_PROMPT_WORKFLOW;

    /** The {@code calc} evaluator, made on first use; kept for this session's lifetime and shared by its runners. */
    private final ConversationToolRunner.Calculator calculator = new ConversationToolRunner.Calculator();
    /** The runner of the tools that do not drive this conversation, made again whenever what they read is set. */
    private ConversationToolRunner toolRunner;

    /**
     * Convenience constructor without the I/O log (used by tests).
     *
     * @param provider     the LLM provider implementation to delegate prompts to
     * @param configApiKey optional API key supplied via application configuration
     */
    public ChatSession(LlmProvider provider, Optional<String> configApiKey) {
        this(provider, configApiKey, null);
    }

    /**
     * Creates a new ChatSession bound to the given LLM provider.
     *
     * <p>Determines the authentication mode by checking (in order):
     * CLI capability, environment variable, and config property.</p>
     *
     * @param provider     the LLM provider implementation to delegate prompts to
     * @param configApiKey optional API key supplied via application configuration
     * @param ioLog        store for the complete I/O log (Sessions tab), or {@code null} to disable logging
     */
    public ChatSession(LlmProvider provider, Optional<String> configApiKey, IoLogStore ioLog) {
        this.ioLog = ioLog;
        bindProvider(provider, configApiKey);
    }

    /**
     * Replaces this conversation's provider with another implementation
     * ({@code CliHarnessProvider_260912_oo01}). The generating side calls this after it has put the
     * new provider under the same child name the old one had, so {@link #providerRef()} and this
     * field agree again. Must run on this actor's own thread, between turns.
     *
     * @param provider     the new provider
     * @param configApiKey optional API key supplied via application configuration
     */
    public void setProvider(LlmProvider provider, Optional<String> configApiKey) {
        bindProvider(provider, configApiKey);
    }

    private void bindProvider(LlmProvider provider, Optional<String> configApiKey) {
        this.provider = provider;
        if (provider.capabilities().supportsWatchdog()) {
            // CLI-based provider: no API key needed, CLI binary handles auth
            this.authMode = AuthMode.CLI;
            this.apiKey = null;
            logger.info("Provider: " + provider.displayName() + " (CLI mode)");
        } else {
            // HTTP-based provider: needs API key
            String envKey = provider.detectEnvApiKey();
            if (envKey != null && !envKey.isBlank()) {
                this.authMode = AuthMode.API_KEY;
                this.apiKey = envKey;
                logger.info("Provider: " + provider.displayName() + " (API key from environment)");
            } else if (configApiKey.isPresent() && !configApiKey.get().isBlank()) {
                this.authMode = AuthMode.API_KEY;
                this.apiKey = configApiKey.get();
                logger.info("Provider: " + provider.displayName() + " (API key from config)");
            } else {
                this.authMode = AuthMode.NONE;
                this.apiKey = null;
                logger.info("Provider: " + provider.displayName() + " (no API key — must be set via Web UI)");
            }
        }
    }

    // ---- Wiring — the generating side (ConversationSquad) sets these; ChatSession creates none of them ----

    /**
     * Makes {@code system.getActor(...)} resolvable from inside this session's own methods.
     * The generating side sets this once, right after {@code createChild}.
     *
     * @param system the actor system this session's ActorRef is registered in
     */
    public void setActorSystem(IIActorSystem system) {
        this.system = system;
        renewToolRunner();
    }

    /**
     * @param providerName the name of the {@code provider} child actor, created by the generating side
     */
    public void setProviderName(String providerName) { this.providerName = providerName; }

    /** @return the name of the {@code provider} child actor */
    public String getProviderName() { return providerName; }

    /**
     * @param watchdogName the name of the sibling StallMonitor, or {@code null}
     */
    public void setWatchdogName(String watchdogName) { this.watchdogName = watchdogName; }

    /**
     * @param promptQueueName the name of the sibling PromptQueue
     */
    public void setPromptQueueName(String promptQueueName) { this.promptQueueName = promptQueueName; }

    /**
     * @param projectId this session's owning project id, e.g. {@code "project1"}
     * @param chatId    this session's conversation id within that project, used to key its
     *                  I/O-log session ({@link IoLogStore#ensureSession(String)})
     */
    public void setChatIdentity(String projectId, String chatId) {
        this.projectId = projectId;
        this.chatId = chatId;
        renewToolRunner();
    }

    /** @return this session's qualified conversation name, e.g. {@code "project1/chat-01"} */
    private String myChatName() { return ChatUiActorSystem.chatActorName(projectId, chatId); }

    /** @param watchdogRef the shared {@link CallWatchdog}, for the {@code ask_chat} tool */
    public void setWatchdogRef(ActorRef<CallWatchdog> watchdogRef) { this.watchdogRef = watchdogRef; }

    /** @param conversationControls the settings and log window the conversation tools use */
    public void setConversationControls(ConversationControls conversationControls) {
        this.conversationControls = conversationControls;
        renewToolRunner();
    }

    /** @param roleTableRef the role table of this conversation's project */
    public void setRoleTableRef(ActorRef<RoleTable> roleTableRef) {
        this.roleTableRef = roleTableRef;
        renewToolRunner();
    }

    /** @param fileScope the range of the file system {@code read}/{@code write} may touch */
    public void setFileScope(FileAccessScope fileScope) {
        this.fileScope = fileScope;
        renewToolRunner();
    }

    /** @param skillRegistryRef the shared {@link SkillRegistry}, for {@code load_skill} */
    public void setSkillRegistryRef(ActorRef<SkillRegistry> skillRegistryRef) {
        this.skillRegistryRef = skillRegistryRef;
        renewToolRunner();
    }

    /** @return the project instructions this conversation sends with each turn, or {@code null} when it has none */
    public String getProjectInstructions() {
        return projectInstructions;
    }

    /**
     * @param projectInstructions this session's project's instructions, or {@code null} for none.
     *                            Re-sent whenever the project's working directory changes, so a
     *                            session that outlives that change follows the new text from its
     *                            next turn onwards.
     */
    public void setProjectInstructions(String projectInstructions) {
        this.projectInstructions = projectInstructions;
    }

    /**
     * Forwards one entry to this session's ConversationSquad log multiplexer ({@code <projectId>/chat-<chatId>.log}), in
     * addition to (not instead of) the existing {@code logger.xxx(...)} calls near each call site —
     * those keep flowing to {@link com.scivicslab.chatui.logging.LogTap} unchanged
     * ({@code 150_TabScopedLogging_260826_oo01} "既存のLOG.xxx()を置き換えず"). Silently no-ops if
     * the actor system or ConversationSquad id isn't wired yet, or the ConversationSquad log actor isn't found.
     */
    private void logToConversationSquad(String type, String message) {
        if (system == null || chatId == null) return;
        try {
            IIActorRef<?> conversationSquadLog = system.getIIActor(myChatName() + ".log");
            if (conversationSquadLog == null) return;
            JSONObject args = new JSONObject();
            args.put("source", "ChatSession");
            args.put("type", type);
            args.put("data", message);
            conversationSquadLog.callByActionName("add", args.toString());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to forward log entry to ConversationSquad log", e);
        }
    }

    /**
     * Swaps the prompt-construction sub-workflow (default: {@code prompt-construction-default.yaml}).
     *
     * @param promptWorkflowFile classpath-relative workflow file, resolved the same way
     *                           {@link Interpreter#call(String)} resolves any sub-workflow
     */
    public void setPromptWorkflowFile(String promptWorkflowFile) { this.promptWorkflowFile = promptWorkflowFile; }

    /** @return this session's prompt-construction sub-workflow file name (classpath-relative, under {@code /workflows/}) */
    public String getPromptWorkflowFile() { return promptWorkflowFile; }

    /**
     * Looks up the sibling PromptQueue by name, on demand — not stored as a field.
     *
     * @return the PromptQueue's ActorRef, or {@code null} if not wired
     */
    public ActorRef<PromptQueue> getPromptQueue() {
        return promptQueueName == null ? null : system.getActor(promptQueueName);
    }

    /** Looks up the {@code provider} child's ActorRef by name, on demand — not stored as a field. */
    /**
     * Runs {@code call} on the provider's own actor and waits for it. The request messages live in
     * the provider, and the end of a turn changes them by messages told to that actor; reading or
     * changing them from this actor's thread directly would race with those
     * ({@code AgentLoopDecidesThePrompt_261020_oo01}). Without a provider actor (a provider used
     * on its own), the provider is called directly.
     */
    private <R> R onProvider(java.util.function.Function<LlmProvider, R> call) {
        ActorRef<LlmProvider> ref = system == null || providerName == null ? null : providerRef();
        if (ref == null) return call.apply(provider);
        try {
            return ref.ask(call::apply).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("the provider did not answer: " + e.getMessage(), e);
        }
    }

    private ActorRef<LlmProvider> providerRef() {
        return system.getActor(providerName);
    }

    // ---- Authentication ----

    /**
     * Returns the authentication mode determined at construction time.
     *
     * @return the current authentication mode (CLI, API_KEY, or NONE)
     */
    public AuthMode getAuthMode() { return authMode; }

    /**
     * Checks whether this actor has sufficient credentials to send prompts.
     *
     * <p>Returns {@code true} when the provider uses CLI auth, or when an API key
     * has been supplied via environment, config, or the Web UI.</p>
     *
     * @return {@code true} if the actor is ready to authenticate with the provider
     */
    public boolean isAuthenticated() {
        return authMode == AuthMode.CLI
                || authMode == AuthMode.NONE
                || (authMode == AuthMode.API_KEY && apiKey != null);
    }

    /**
     * Sets the API key, typically called when a user provides one through the Web UI.
     *
     * @param key the API key to store
     */
    public void setApiKey(String key) {
        this.apiKey = key;
        logger.info("API key set via Web UI");
    }

    /**
     * Returns the currently stored API key, or {@code null} if none is set.
     *
     * @return the API key, or {@code null}
     */
    public String getApiKey() { return apiKey; }

    // ---- Provider delegation (cheap synchronous reads — call the plain `provider` field directly) ----

    /**
     * Returns whether a prompt is currently being processed.
     *
     * @return {@code true} if the actor is busy with an LLM request
     */
    public boolean isBusy() { return busy; }

    /**
     * @return when the running turn started, in milliseconds since the epoch, or {@code null} when no turn is
     *         running; read without waiting, like {@link #isBusy()}
     */
    public Long runningSince() { return busy ? turnStartedAt : null; }

    /**
     * The conversation history as of the last {@link #recordHistory} call, safe to read from any
     * thread without going through this actor's mailbox (see {@code
     * BusyStateReadableSnapshot_260828_oo01}).
     *
     * @return an immutable snapshot of the conversation history
     */
    public List<HistoryEntry> historySnapshot() { return historySnapshot.get(); }

    /**
     * Returns the model identifier currently selected by the provider.
     *
     * @return the active model name
     */
    public String getModel() { return provider.getCurrentModel(); }

    /** @return the provider's kind id, e.g. {@code openai-compat} or {@code claude} */
    public String getProviderId() { return provider.id(); }

    /** @see LlmProvider#persistedLog(int, long) */
    public com.scivicslab.chatui.core.provider.PersistedLog persistedLog(int limit, long before) {
        return provider.persistedLog(limit, before);
    }

    /**
     * Returns the current provider session identifier, or {@code null} if no session is active.
     *
     * @return the session ID
     */
    public String getSessionId() { return provider.getSessionId(); }

    /**
     * Tests whether the given input string is a provider command (e.g. slash command).
     *
     * @param input the user input to check
     * @return {@code true} if the provider recognises this as a command
     */
    public boolean isCommand(String input) { return provider.isCommand(input); }

    /**
     * Returns the list of models available from the current provider.
     *
     * @return an unmodifiable list of model entries
     */
    public List<LlmProvider.ModelEntry> getAvailableModels() { return provider.getAvailableModels(); }

    /**
     * Delegates a slash command to the provider and returns the resulting events.
     *
     * <p>If the command is {@code /clear}, the conversation history is also cleared.
     * A status event is always appended to the response list.</p>
     *
     * @param input the raw command string entered by the user
     * @return a list of {@link ChatEvent}s produced by the command
     */
    public List<ChatEvent> handleCommand(String input) {
        List<ChatEvent> responses = new ArrayList<>(provider.handleCommand(input));
        if (provider.isExchangeWithBackend(input)) {
            recordCommandIo(input.trim(), responses);
        }
        if (input.trim().toLowerCase().startsWith("/clear")) {
            clearHistory();
        }
        responses.add(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), busy));
        return responses;
    }

    // ---- Chat lifecycle ----

    /**
     * Begins an asynchronous prompt. Dispatches blocking LLM I/O onto the managed thread pool,
     * returning immediately so the actor can process other messages (cancel, log, etc.) while
     * the request is in flight.
     *
     * <p>Convenience overload with no {@code resultKey} (human-typed prompts) and
     * {@code noThink} left at its default ({@code false}).</p>
     *
     * @param prompt the prompt text to send to the LLM
     * @param model  the model to use, or {@code null}/blank to keep the provider's current model
     * @param emitter callback that receives {@link ChatEvent}s as the response streams in
     * @param self   this actor's own reference, used to queue completion back onto the actor thread
     * @param done   completed with the turn's {@link TurnReply} once the prompt has finished processing (success or error)
     */
    public void startPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                            ActorRef<ChatSession> self, CompletableFuture<TurnReply> done) {
        startPrompt(prompt, model, emitter, self, done, null, false);
    }

    /**
     * Begins an asynchronous prompt with MCP result accumulation.
     *
     * <p>Convenience overload with {@code noThink} left at its default ({@code false}).</p>
     *
     * @param prompt    the prompt text to send to the LLM
     * @param model     the model to use, or {@code null}/blank to keep the provider's current model
     * @param emitter   callback that receives {@link ChatEvent}s as the response streams in
     * @param self      this actor's own reference, used to queue completion back onto the actor thread
     * @param done      completed with the turn's {@link TurnReply} once the prompt has finished processing (success or error)
     * @param resultKey UUID under which the accumulated response is stored for later retrieval via
     *                  {@link #getCompletedResult(String)}, or {@code null} for human-typed prompts
     */
    public void startPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                            ActorRef<ChatSession> self, CompletableFuture<TurnReply> done,
                            String resultKey) {
        startPrompt(prompt, model, emitter, self, done, resultKey, false);
    }

    /**
     * Begins an asynchronous prompt with optional result accumulation.
     *
     * <p>When {@code resultKey} is non-null (MCP-submitted prompts), the full assistant
     * response text is accumulated and stored in {@code completedResults} under that key
     * so that {@link #getCompletedResult(String)} can return it after completion.</p>
     *
     * @param prompt    the prompt text to send to the LLM
     * @param model     the model to use, or {@code null}/blank to keep the provider's current model
     * @param emitter   callback that receives {@link ChatEvent}s as the response streams in
     * @param self      this actor's own reference, used to queue completion back onto the actor thread
     * @param done      completed with the turn's {@link TurnReply} once the prompt has finished processing (success or error)
     * @param resultKey UUID under which the accumulated response is stored for later retrieval via
     *                  {@link #getCompletedResult(String)}, or {@code null} for human-typed prompts
     * @param noThink   whether to ask the provider to skip its reasoning/thinking phase
     */
    public void startPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                            ActorRef<ChatSession> self, CompletableFuture<TurnReply> done,
                            String resultKey, boolean noThink) {
        if (busy) {
            emitter.accept(ChatEvent.error("Already processing a prompt. Please wait or cancel."));
            done.complete(TurnReply.NONE);
            return;
        }
        if (!isAuthenticated()) {
            emitter.accept(ChatEvent.error(
                    "No authentication configured. Please provide an API key."));
            done.complete(TurnReply.NONE);
            return;
        }

        busy = true;
        // Open (lazily) the conversation's I/O-log session and number this turn for the Sessions tab.
        final long ioSession = (ioLog != null) ? ioLog.ensureSession(myChatName()) : -1;
        final int ioTurnNo = ++ioTurn;
        this.ioSession = ioSession;
        this.ioTurnNo = ioTurnNo;
        this.turnRequestId = null;
        this.askCount = 0;
        this.turnOriginal = null;
        this.turnAnswer = null;
        this.cancelled = false;
        this.usageLimitMessage = null;
        this.turnError = null;
        this.turnTranscript.setLength(0);
        this.pauseRequested = false;
        recordHistory("user", prompt);
        if (resultKey != null) {
            pendingResultKeys.remove(resultKey);
            activeResultKey = resultKey;
        }
        // StallMonitor is not yet ported (ChatSessionPorting_260823_oo01 Under the Hood);
        // watchdogName is always null for the openai-compat provider this class targets.
        boolean useWatchdog = false;

        final String snapApiKey = apiKey;
        ActorRef<LlmProvider> providerRef = providerRef();

        // Delegate blocking I/O to the provider child actor on the managed thread pool
        // (real threads). Actor message loops run on virtual threads, so long-running
        // blocking I/O must be dispatched to the managed pool.
        // This actor returns immediately and remains free for other messages (cancel, log).
        // whenComplete() queues onPromptComplete() back onto this actor when done.
        providerRef.ask(p -> {
            try {
                if (model != null && !model.isBlank()) p.setModel(model);

                Runnable heartbeat = () -> {};

                ProviderContext ctx = new ProviderContext(snapApiKey, List.of(), noThink, heartbeat);

                // Wrap emitter to intercept assistant content for history and optional result capture
                StringBuilder assistantBuf = new StringBuilder();
                StringBuilder thinkingBuf = new StringBuilder();
                StringBuilder resultBuf = (resultKey != null) ? new StringBuilder() : null;
                Consumer<ChatEvent> wrappedEmitter = event -> {
                    if ("delta".equals(event.type()) && event.content() != null) {
                        assistantBuf.append(event.content());
                        if (resultBuf != null) resultBuf.append(event.content());
                    } else if ("thinking".equals(event.type()) && event.content() != null) {
                        thinkingBuf.append(event.content());
                    } else if ("result".equals(event.type())) {
                        if (!assistantBuf.isEmpty()) {
                            String content = assistantBuf.toString();
                            self.tell(b -> b.recordHistory("assistant", content));
                        }
                        if (resultBuf != null) {
                            String captured = resultBuf.toString();
                            self.tell(b -> b.storeCompletedResult(resultKey, captured));
                        }
                        // Persist the completed turn to the I/O log in the Sessions-tab marker format.
                        recordTurnIo(ioSession, ioTurnNo, prompt, assistantBuf.toString(), thinkingBuf.toString());
                    }
                    emitter.accept(event);
                };

                emitter.accept(ChatEvent.status(p.getCurrentModel(), p.getSessionId(), true));
                p.sendPrompt(prompt, p.getCurrentModel(), wrappedEmitter, ctx);

            } catch (Exception e) {
                logger.log(Level.WARNING, "Provider sendPrompt failed", e);
                emitter.accept(ChatEvent.error("Error: " + e.getMessage()));
            }
            return null;
        }, system.getManagedThreadPool())
        .whenComplete((r, ex) -> self.tell(b -> b.onPromptComplete(emitter, done, self)));
    }

    /**
     * Called when LLM processing finishes; queued back onto the actor via {@code self.tell()}.
     *
     * @param emitter callback that receives the final {@link ChatEvent} status update
     * @param done    completed with the turn's answer and ending line, or {@link TurnReply#NONE} when it left none
     * @param self    this actor's own reference, forwarded to {@code PromptQueue} to dispatch the next prompt
     */
    public void onPromptComplete(Consumer<ChatEvent> emitter, CompletableFuture<TurnReply> done, ActorRef<ChatSession> self) {
        // The answer goes out in done itself. Whoever waits on done must not have to ask this actor
        // for it afterwards: by then this actor may already be running the next queued prompt's turn,
        // and the question would wait behind it (AskChatWithoutWaiting_261022_oo01).
        TurnReply reply = activeResultKey == null
                ? TurnReply.NONE
                : new TurnReply(completedResults.get(activeResultKey), completedEndings.get(activeResultKey), turnId());
        // Every turn that ends with no answer says why, here where every turn closes, whichever provider ran it
        // (TurnWithoutAnAnswerTellsTheAsker_261119_oo01).
        TurnReply closing = turnAnswer != null && turnAnswer.reply() != null ? turnAnswer
                : new TurnReply(null, noAnswerReason(), turnId());
        if (activeResultKey != null && reply.reply() == null) reply = closing;
        // A turn that works for a request this conversation received answers it only when none of the
        // requests sent for it is still out; until then the asker's done stays open, and the answers
        // of every turn that worked for the request go in it, each under its turn's id
        // (ReportReachesTheAskerAfterDelegation_261107_oo01).
        CompletableFuture<TurnReply> originalDone = null;
        // A turn stopped by stalled-turn recovery has not done the work either: its prompt goes back to the front of
        // the queue and runs again, so nothing of this turn's ending reaches anyone (StalledTurnRecovery).
        // A turn the model's service refused for the usage limit has not done the work either: its request goes back to
        // the front and the queue is held, so the waiting requests are not failed one after another while the limit
        // lasts; a stalled-turn recovery releases it (tasks/stalled-turn-recovery.md).
        boolean limited = usageLimitMessage != null && !recoverRequested;
        boolean recovering = recoverRequested || limited;
        boolean paused = pauseRequested || recovering;
        if (turnOriginal != null) {
            originalDone = openRequests.doneOf(turnOriginal);
            // A paused turn has not finished the work: the request waits for the turn that resumes it.
            if (!paused) {
                openRequests.turnEnded(turnOriginal, closing);
                recordOpenRequests();
            }
        }
        // The answer of a turn a reminder started goes to whoever waits for the report, as a follow-up
        // to the same request (ReplyDecisionAndPromisedReport_261108_oo01).
        if (turnReminder != null && turnFollowUp != null && !paused
                && !REMINDER_TO_SCREEN.equals(turnFollowUp.asker())) {
            AskChatTool.followUp(system, myChatName(), turnFollowUp.asker(), turnReminder, closing, turnFollowUp.number(),
                    turnFollowUp.link());
        }
        // Kept with the paused turn first, then cleared.
        if (paused && !recovering) queueResume(emitter, self);
        turnReminder = null;
        turnFollowUp = null;
        pauseRequested = false;
        recoverRequested = false;
        abandonCall = false;
        llmCallStartedAt = 0;
        publishOutstanding();
        ActorRef<PromptQueue> promptQueue = getPromptQueue();
        // Told before busy clears, so whoever sees this conversation idle after a recovery finds its prompt back in
        // the queue once the queue has read its mail (StalledTurnRecovery.recover).
        if (limited) {
            limitHeldSince = System.currentTimeMillis();
            String why = usageLimitMessage;
            logToConversationSquad("WARNING", turnId() + " was refused for the usage limit (" + why + "); its request is"
                    + " back at the front and the queue is held until a stalled-turn recovery releases it");
        }
        usageLimitMessage = null;
        if (promptQueue != null && recovering) promptQueue.tell(q -> q.returnRunningToFront(self, limited));
        busy = false;
        activeResultKey = null;

        if (promptQueue != null && !recovering) {
            promptQueue.tell(q -> q.onPromptComplete(self));
        }

        emitter.accept(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), false));
        if (done != originalDone && !recovering) done.complete(reply);
    }

    /** Why a turn that left no answer has none: what its asker is told ({@code TurnWithoutAnAnswerTellsTheAsker_261119_oo01}). */
    private String noAnswerReason() {
        if (turnAnswer != null && turnAnswer.ending() != null) return turnAnswer.ending();
        if (cancelled) return "its turn was cancelled";
        if (turnError != null) return "its turn failed: " + turnError;
        return "its turn ended without an answer";
    }

    /** What the asker of a request whose turn a restart cut is told. */
    public static final String RESTARTED_DURING_ITS_TURN = "the instance restarted during its turn";

    /**
     * After a restart, once the open requests and the queue are back: a request this conversation received and has
     * not answered, that waits for no request it sent and that no queued message will work on, was being worked on
     * by a turn the restart cut. Its asker is told so; the work is not run again
     * ({@code TurnWithoutAnAnswerTellsTheAsker_261119_oo01}).
     *
     * @param queuedRequestIds the request ids the queue's waiting messages carry, a resume of a paused turn included
     */
    public void closeRequestsCutByRestart(java.util.Set<String> queuedRequestIds) {
        List<String> closed = openRequests.cutByRestart(queuedRequestIds, RESTARTED_DURING_ITS_TURN);
        if (closed.isEmpty()) return;
        for (String id : closed) {
            logToConversationSquad("WARNING", "request " + id + " was in a turn the restart cut; its asker is told: "
                    + RESTARTED_DURING_ITS_TURN);
        }
        publishOutstanding();
    }

    // ---- Agent loop (ChatSessionAgentLoop_260823_oo01) ----
    // Driven by chat-session-agent-loop.yaml: (stepExpectingAction -> runTool)* -> finish.
    // Callers must invoke start(...) then runUntilEnd() (inherited from Interpreter) as plain Java
    // method calls from within an existing tell()/ask() closure — never through
    // ChatSessionIIAR's generic callByActionName("runUntilEnd", ...), which would dispatch onto
    // IIActorSystem's ManagedThreadPool and mutate these fields off this actor's own thread.

    /**
     * Begins an agent-loop turn: resets per-turn state and positions the state machine at
     * "think" so a following {@code runUntilEnd()} call runs the loop to completion.
     *
     * @param prompt    the user's prompt text
     * @param model     the model to use, or {@code null}/blank to keep the provider's current model
     * @param emitter   callback that receives {@link ChatEvent}s as the turn streams
     * @param self      this actor's own reference, used for hand-offs (e.g. {@code onPromptComplete})
     * @param done      completed with the turn's {@link TurnReply} once the turn has finished processing (success or error)
     * @param resultKey UUID under which the final answer is stored for later retrieval via
     *                  {@link #getCompletedResult(String)}, or {@code null} for human-typed prompts
     * @param noThink   whether to ask the provider to skip its reasoning/thinking phase for
     *                  every LLM call in this turn (see {@link #stepExpectingAction()})
     */
    public void start(String prompt, String model, Consumer<ChatEvent> emitter,
                       ActorRef<ChatSession> self, CompletableFuture<TurnReply> done, String resultKey,
                       boolean noThink) {
        start(prompt, model, emitter, self, done, resultKey, noThink, List.of());
    }

    /**
     * Starts a turn with images attached to its prompt ({@code AttachedImages_260908_oo01}).
     *
     * @param images data URLs pasted/dropped/attached alongside {@code prompt}, sent to the
     *               provider only on this turn's first LLM call — see {@link #pendingImages}
     */
    public void start(String prompt, String model, Consumer<ChatEvent> emitter,
                       ActorRef<ChatSession> self, CompletableFuture<TurnReply> done, String resultKey,
                       boolean noThink, List<String> images) {
        start(prompt, model, emitter, self, done, resultKey, noThink, images, null);
    }

    /**
     * Starts a turn, saying where its prompt came from.
     *
     * @param source what the queue recorded about the sender ({@link #originOf(String)}); the
     *               conversation keeps it on the user's line so a reader can tell a person's own
     *               prompt from a workflow's ({@code PromptOriginOnScreen_260915_oo01})
     */
    public void start(String prompt, String model, Consumer<ChatEvent> emitter,
                       ActorRef<ChatSession> self, CompletableFuture<TurnReply> done, String resultKey,
                       boolean noThink, List<String> images, String source) {
        start(prompt, model, emitter, self, done, resultKey, noThink, images, source, null);
    }

    /**
     * Starts a turn whose prompt is a request from, or a reply to, another conversation's
     * {@code ask_chat} ({@code TurnAndRequestIds_261107_oo01}).
     *
     * @param requestId the request the prompt is, or answers; {@code null} for any other prompt.
     *                  Kept on the user's line and on this turn's answer in the record.
     */
    public void start(String prompt, String model, Consumer<ChatEvent> emitter,
                       ActorRef<ChatSession> self, CompletableFuture<TurnReply> done, String resultKey,
                       boolean noThink, List<String> images, String source, String requestId) {
        start(prompt, model, emitter, self, done, resultKey, noThink, images, source, requestId, null);
    }

    /**
     * @param link the way back to the sender when the request came from another chat-ui instance, or {@code null};
     *             recorded with the received request, so its reply survives a restart
     */
    public void start(String prompt, String model, Consumer<ChatEvent> emitter,
                       ActorRef<ChatSession> self, CompletableFuture<TurnReply> done, String resultKey,
                       boolean noThink, List<String> images, String source, String requestId,
                       OpenRequests.RemoteLink link) {
        if (busy) {
            emitter.accept(ChatEvent.error("Already processing a prompt. Please wait or cancel."));
            done.complete(TurnReply.NONE);
            return;
        }
        if (!isAuthenticated()) {
            emitter.accept(ChatEvent.error("No authentication configured. Please provide an API key."));
            done.complete(TurnReply.NONE);
            return;
        }

        busy = true;
        this.turnSource = source;
        this.ioSession = (ioLog != null) ? ioLog.ensureSession(myChatName()) : -1;
        this.ioTurnNo = ++ioTurn;
        this.turnRequestId = requestId;
        this.askCount = 0;
        this.turnAnswer = null;
        if (requestId != null && source != null && source.startsWith(ASK_CHAT_SOURCE_PREFIX)) {
            // Whether it is urgent is in the heading this instance put on it ("[urgent request ...").
            openRequests.received(requestId, source.substring(ASK_CHAT_SOURCE_PREFIX.length()), done,
                    prompt != null && prompt.startsWith("[urgent request "), link);
            this.turnOriginal = requestId;
        } else if (requestId != null && source != null && source.startsWith(ASK_CHAT_REPLY_SOURCE_PREFIX)) {
            // A follow-up is the answer of the promiser's reminder; it ends the promise, and is the reply only when the
            // request had had its reply before (AutomaticReplyDecision_261119_oo01).
            this.turnOriginal = prompt != null && prompt.startsWith("[follow-up to request ")
                    ? openRequests.followUpArrived(requestId, System.currentTimeMillis())
                    : openRequests.replyArrived(requestId);
        } else if (isResume(source)) {
            // Goes on with the work of a paused turn, for the same original request.
            this.turnOriginal = openRequests.isOpen(requestId) ? requestId : null;
        } else {
            this.turnOriginal = null;
        }
        if (requestId != null && source != null
                && (source.startsWith(ASK_CHAT_SOURCE_PREFIX) || source.startsWith(ASK_CHAT_REPLY_SOURCE_PREFIX))) {
            recordOpenRequests();
        }
        this.turnTranscript.setLength(0);
        this.pauseRequested = false;
        publishOutstanding();
        recordHistory("user", prompt, originOf(source), null, null);
        // The prompt is already on the screen (the queue echoed it at dispatch); this puts the turn's
        // id and request id under it (TurnAndRequestIds_261107_oo01).
        emitter.accept(ChatEvent.turn(turnId(), requestId));
        recordTurnIds(requestId);
        if (resultKey != null) {
            pendingResultKeys.remove(resultKey);
            activeResultKey = resultKey;
        }

        // A turn that resumes a paused one takes over everything the paused turn knew about its purpose.
        PausedTurn resumed = isResume(source) ? pausedTurns.remove(pausedTurnIdOf(source)) : null;
        // A reply this conversation must decide on (ReplyDecisionAndPromisedReport_261108_oo01).
        boolean isReply = requestId != null && source != null && source.startsWith(ASK_CHAT_REPLY_SOURCE_PREFIX);
        this.undecidedRequest = resumed != null ? resumed.undecidedRequest() : isReply ? requestId : null;
        this.undecidedReplier = resumed != null ? resumed.undecidedReplier()
                : isReply ? source.substring(ASK_CHAT_REPLY_SOURCE_PREFIX.length()) : null;
        this.undecidedEnding = resumed != null ? resumed.undecidedEnding() : isReply ? replyEndingOf(prompt) : null;
        this.replyDecisionSettled = false;
        this.decisionAsked = false;
        this.answerBeforeDecision = null;
        // A reminder's answer goes to whoever waits for the report.
        FollowUpTo reminder = reminderOf(source);
        // The way back to an asker on another instance came with the reminder from the promise's record.
        if (reminder != null && link != null) reminder = new FollowUpTo(reminder.asker(), reminder.number(), link);
        boolean isReminder = reminder != null && requestId != null;
        this.turnReminder = isReminder ? requestId : resumed != null ? resumed.reminderRequest() : null;
        this.turnFollowUp = isReminder ? reminder : resumed != null ? resumed.followUp() : null;

        this.turnTask = resumed != null && resumed.task() != null ? resumed.task() : prompt;
        this.question = prompt;
        this.pendingPrompt = prompt;
        this.pendingImages = (images == null) ? List.of() : images;
        this.turnModel = model;
        this.turnEmitter = emitter;
        this.turnSelf = self;
        this.turnDone = done;
        // Either side may ask for it: the conversation's own setting, or this one prompt.
        this.turnNoThink = noThink || noThinkAlways;
        this.turnStartedAt = System.currentTimeMillis();
        if (byDefinition()) handSystemMessage();
        this.pendingCalls = null;
        this.finalAnswer = null;
        this.turnError = null;
        this.stepCount = 0;
        this.turnToolCalls = 0;
        this.stepLimitHit = false;
        this.personQuestion = null;
        this.cancelled = false;
        this.usageLimitMessage = null;
        this.constructedPrompts.clear();
        this.cutOffPending = false;
        this.cutOffRun = 0;
        this.cutOffChars = 0;
        this.cutOffText.setLength(0);
        this.repairPending = false;
        this.repairRun = 0;
        this.repairText = null;
        this.repairProblems = List.of();
        this.repairTool = null;
        this.requestMessagesToRestore = null;
        this.lastWorkerReply = null;
        this.lastJudgment = null;
        this.redoNote = null;
        this.redoCount = 0;
        this.lastCollaborationError = null;

        emitter.accept(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), true));
        // transitionTo (not setCurrentState) also repositions the transition-scan cursor, so a
        // second turn resumes scanning from the top and finds think-action before think-final
        // (see chat-session-agent-loop.yaml's own note).
        transitionTo("think");
    }

    /**
     * Pushes one constructed prompt onto the queue {@link #stepExpectingAction()} drains, one per
     * LLM call. A prompt-construction sub-workflow calls this — possibly more than once per
     * invocation (e.g. to split one turn's input into several prompts sent as separate LLM
     * turns) — to hand back the text it built.
     *
     * @param prompt the prompt text to send to {@code provider.sendPrompt(...)} on a future step
     */
    public void appendConstructedPrompt(String prompt) {
        constructedPrompts.addLast(new Constructed(prompt, null));
    }

    /**
     * Pushes the whole request messages for this turn's request: a prompt-construction sub-workflow
     * that built the array itself (with PromptBuilder's {@code buildMessages}) calls this instead of
     * {@link #appendConstructedPrompt}. The last message is the user message; the ones before it
     * are what the request carries besides it. They are used for this turn only: when the turn ends
     * the provider's request messages are put back as they were, and only the question and the
     * answer are added.
     *
     * @param messagesJson a JSON array of {@code {"role","content"}} whose last element has role {@code user}
     * @return {@link ActionResult} with {@code success=true} iff the array was usable
     */
    public ActionResult appendConstructedMessages(String messagesJson) {
        org.json.JSONArray all;
        try {
            all = new org.json.JSONArray(messagesJson);
        } catch (org.json.JSONException e) {
            return new ActionResult(false, "not a JSON array: " + e.getMessage());
        }
        if (all.isEmpty()) return new ActionResult(false, "the array is empty");
        org.json.JSONObject last = all.optJSONObject(all.length() - 1);
        if (last == null || !"user".equals(last.optString("role")) || !(last.opt("content") instanceof String)) {
            return new ActionResult(false, "the last message must be a user message with text");
        }
        org.json.JSONArray before = new org.json.JSONArray();
        for (int i = 0; i < all.length() - 1; i++) before.put(all.get(i));
        constructedPrompts.addLast(new Constructed(last.getString("content"), before.toString()));
        return new ActionResult(true, "queued " + all.length() + " messages");
    }

    /**
     * What this conversation has said so far as questions and answers, for a prompt-construction
     * sub-workflow to put in the request or leave out: the provider's request messages without any
     * {@code system} message. {@code []} when the provider keeps none.
     *
     * @return {@link ActionResult} carrying the JSON array, always successful
     */
    public ActionResult pastTurns() {
        String json;
        try {
            json = providerRef().ask(p -> p.requestMessagesJson()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ActionResult(false, "could not read the request messages: " + e.getMessage());
        }
        if (json == null) return new ActionResult(true, "[]");
        org.json.JSONArray kept = new org.json.JSONArray();
        org.json.JSONArray all = new org.json.JSONArray(json);
        for (int i = 0; i < all.length(); i++) {
            if (!"system".equals(all.getJSONObject(i).optString("role"))) kept.put(all.get(i));
        }
        return new ActionResult(true, kept.toString());
    }

    /**
     * Whether this is the turn's first request. The later ones carry a tool's observation, which a
     * sub-workflow sends as plain text, in the messages the first request left in the provider.
     *
     * @return {@link ActionResult} with {@code success=true} iff this is the first request of the turn
     */
    public ActionResult isFirstStep() {
        return new ActionResult(stepCount == 1, stepCount == 1 ? "first step" : "step " + stepCount);
    }

    /**
     * Puts the given request messages into the provider for this turn, remembering the ones it had.
     *
     * @return {@code null} when done, otherwise why not
     */
    private String useRequestMessagesForThisTurn(String json) {
        try {
            if (requestMessagesToRestore == null) {
                String before = providerRef().ask(p -> p.requestMessagesJson()).get(10, TimeUnit.SECONDS);
                if (before == null) return "this provider keeps no request messages";
                requestMessagesToRestore = before;
            }
            return providerRef().ask(p -> p.replaceRequestMessages(json)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "could not set the request messages: " + e.getMessage();
        }
    }

    /**
     * Cheap check ({@code chat-session-agent-loop.yaml}'s {@code think-continue} transition) —
     * no LLM call. After a step whose response had no tool call, distinguishes "nothing else
     * queued, this is the turn's final answer" (go to {@code finish}) from "the prompt-
     * construction sub-workflow queued more than one prompt, more remain" (loop back to
     * {@code stepExpectingAction} for the next one).
     *
     * @return {@link ActionResult} with {@code success=true} iff prompts remain queued
     */
    public ActionResult hasMoreConstructedPrompts() {
        // A question to the person ends the turn; the prompts still queued wait for the answer.
        if (personQuestion != null) return new ActionResult(false, "asked the person");
        boolean more = !constructedPrompts.isEmpty();
        return new ActionResult(more, more ? "more queued" : "empty");
    }

    /**
     * Default prompt-construction strategy ({@code prompt-construction-default.yaml}'s only
     * action): reproduces what {@code stepExpectingAction()} built inline before this sub-workflow
     * indirection existed — the system prompt prefixed to the turn's first step, the
     * tool-observation-appended {@code pendingPrompt} verbatim on later steps.
     */
    public void buildDefaultPrompt() {
        appendConstructedPrompt(currentPromptText().getResult());
    }

    /**
     * The same text {@link #buildDefaultPrompt()} would queue, returned instead of queued
     * ({@code DocRetrievalAgentLoop_260830_oo01}).
     *
     * <p>A prompt-construction sub-workflow that wraps this turn's text in something else — the
     * constraints {@code PromptBuilderActor} puts under {@code [Constraints]}, for instance — needs
     * the text as a value it can pass on, not as an entry already in the queue.</p>
     *
     * @return {@link ActionResult} carrying the turn's text, always successful
     */
    public ActionResult currentPromptText() {
        String opening = stepCount == 1 ? firstStepPrompt() : "";
        String text = opening.isEmpty() ? pendingPrompt : opening + "\n\n" + pendingPrompt;
        return new ActionResult(true, text == null ? "" : text);
    }

    /**
     * This turn's own text — what the person typed on the first step, the tool's observation on
     * the later ones — with none of the opening text in front. A prompt-construction sub-workflow
     * that wants to send the text alone (a conversation that only writes prose) starts from this.
     *
     * @return {@link ActionResult} carrying the text, always successful
     */
    public ActionResult turnText() {
        return new ActionResult(true, pendingPrompt == null ? "" : pendingPrompt);
    }

    /** @return {@link ActionResult} carrying part 1 of the opening text, see {@link #toolInstructionsText()} */
    public ActionResult toolInstructions() { return new ActionResult(true, byDefinition() ? "" : toolInstructionsText()); }

    /** @return {@link ActionResult} carrying part 2 of the opening text, see {@link #skillCatalogPart()} */
    public ActionResult skillCatalog() { return new ActionResult(true, byDefinition() ? "" : skillCatalogPart()); }

    /** @return {@link ActionResult} carrying part 3 of the opening text, see {@link #projectInstructionsPart()} */
    public ActionResult projectInstructions() { return new ActionResult(true, byDefinition() ? "" : projectInstructionsPart()); }

    /**
     * The turn's opening text: the tool instructions, then the two things that vary between
     * conversations — the skill catalog and this project's instructions
     * ({@code SkillAndAgentsFile_260830_oo01}). Each is its own part, so a prompt-construction
     * sub-workflow can take some of them and leave the others out
     * ({@code AgentLoopDecidesThePrompt_261020_oo01}); this joins all three, which is what the
     * default sub-workflow sends.
     *
     * @return the text prefixed to this turn's first prompt
     */
    private String firstStepPrompt() {
        // The same three parts are the system message instead (OpenAiCompatStandardToolCalling_261120_oo01).
        if (byDefinition()) return "";
        return toolInstructionsText() + skillCatalogPart() + projectInstructionsPart();
    }

    /**
     * This conversation's external MCP servers, by name ({@code ExternalMcpServersOfAConversation_261120_oo01}). Kept
     * here rather than by the provider, which another provider replaces. Volatile: the REST view reads it.
     */
    private volatile Map<String, String> mcpServers = Map.of();

    /** @param servers the external MCP servers, checked by {@link McpServerList#check}; recorded by the caller */
    public void setMcpServers(Map<String, String> servers) {
        this.mcpServers = McpServerList.check(servers);
    }

    /** @return the external MCP servers, by name */
    public Map<String, String> getMcpServers() {
        return mcpServers;
    }

    /** @return whether {@code tool} is a tool of one of this conversation's external MCP servers, {@code <server>__<tool>} */
    private boolean isExternalTool(String tool) {
        int at = tool == null ? -1 : tool.indexOf(com.scivicslab.chatui.openaicompat.OpenAiCompatProvider.EXTERNAL_SEPARATOR);
        return at > 0 && mcpServers.containsKey(tool.substring(0, at));
    }

    /**
     * Gives the provider this turn's external MCP servers and system message; the provider also reads its tool list
     * again.
     */
    private void handSystemMessage() {
        String text = systemMessageText();
        Map<String, String> servers = mcpServers;
        try {
            providerRef().ask(p -> { p.setMcpServers(servers); p.setSystemMessage(text); return null; },
                    system.getManagedThreadPool()).get();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not give the provider the system message", e);
        }
    }

    /** @return whether this conversation's provider gives the model its tools as definitions */
    private boolean byDefinition() {
        return provider != null && provider.callsToolsByDefinition();
    }

    /**
     * The system message of a conversation whose provider gives the model its tools as definitions: what the
     * provider says about itself, the harness's notes, where the file tools may reach, the skill catalog and
     * the project's instructions ({@code OpenAiCompatStandardToolCalling_261120_oo01}).
     *
     * @return the text
     */
    String systemMessageText() {
        return provider.promptPreface() + HARNESS_NOTES + fileScopeNote() + skillCatalogPart() + projectInstructionsPart();
    }

    /** @return where read and write may reach, with its leading blank line; {@code ""} without read */
    private String fileScopeNote() {
        if (!toolSet.contains("read")) return "";
        return "\n\nread may read files under: " + fileScope.describeReadRoots()
                + "\nwrite may only write under: " + fileScope.writeRoot()
                + "\nA relative path is taken from the write directory. When a skill's text points"
                + " at a file in its own directory, read it — that directory is readable.";
    }

    /**
     * Part 1 of the opening text: how to call a tool and what each tool this conversation's
     * {@link ToolSet} contains does, then the rules for using them. Assembled here rather than
     * compiled into {@link #SYSTEM_PROMPT} because the descriptions depend on the tool set and on
     * the plugin tools registered.
     */
    String toolInstructionsText() {
        // What the provider needs said about itself comes first — a harness's own tools, how to
        // reach them (HarnessPrefaceAndToolSplit_260912_oo01); a plain model adds nothing.
        StringBuilder buf = new StringBuilder(provider.promptPreface());
        buf.append(SYSTEM_PROMPT_HEAD);
        for (Map.Entry<String, String> tool : TOOL_DESCRIPTIONS.entrySet()) {
            if (toolSet.contains(tool.getKey())) buf.append(tool.getValue());
        }
        for (ConversationTool tool : pluginTools.values()) {
            if (toolSet.contains(tool.name())) buf.append(tool.description());
        }
        buf.append("\n").append(SYSTEM_PROMPT_TAIL).append(fileScopeNote());
        return buf.toString();
    }

    /** Part 2 of the opening text: the skill catalog with its leading blank line, or {@code ""} when there is none. */
    String skillCatalogPart() {
        String catalog = skillCatalogText();
        return catalog.isEmpty() ? "" : "\n\n" + catalog;
    }

    /**
     * Part 3 of the opening text: this project's instructions with their lead-in, then this conversation's
     * place in the project's organization ({@code ConversationReportingLines_261108_oo01}); {@code ""} when
     * there is neither. The position is read afresh on every turn, so a change of superior shows at once.
     */
    String projectInstructionsPart() {
        StringBuilder sb = new StringBuilder();
        if (projectInstructions != null && !projectInstructions.isBlank()) {
            sb.append("\n\nInstructions for this project, from its working directory. They govern"
                    + " the work you do here:\n\n").append(projectInstructions);
        }
        String position = positionText();
        if (position != null) sb.append("\n\n").append(position);
        return sb.toString();
    }

    /** @return this conversation's place in its project's organization, or {@code null} when it has none */
    private String positionText() {
        if (conversationControls == null || projectId == null || chatId == null) return null;
        try {
            return conversationControls.positionOf(projectId, chatId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not read this conversation's place in the organization", e);
            return null;
        }
    }

    /** @return the skill catalog, or {@code ""} when no registry is wired or none could be read */
    private String skillCatalogText() {
        if (skillRegistryRef == null) return "";
        try {
            return skillRegistryRef.ask(SkillRegistry::catalogText).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not read the skill catalog", e);
            return "";
        }
    }

    /**
     * One LLM call. Succeeds (state moves to "act") when the response contains a tool-call
     * request; fails ("think" -> "end" via {@code finish}) when it is a plain final answer, the
     * step limit was reached, the turn was cancelled, or the call errored.
     *
     * @return {@link ActionResult} with {@code success=true} if a tool call was found
     */
    public ActionResult stepExpectingAction() {
        if (cancelled) {
            finalAnswer = null;
            return new ActionResult(false, "cancelled");
        }
        stepCount++;

        if (constructedPrompts.isEmpty()) {
            ActionResult built = call(promptWorkflowFile);
            if (constructedPrompts.isEmpty()) {
                finalAnswer = null;
                turnEmitter.accept(ChatEvent.error("Prompt construction failed: " + built.getResult()));
                return new ActionResult(false, "prompt construction error: " + built.getResult());
            }
        }
        Constructed next = constructedPrompts.pollFirst();
        String promptToSend = next.prompt();
        if (next.requestMessagesJson() != null) {
            String refused = useRequestMessagesForThisTurn(next.requestMessagesJson());
            if (refused != null) {
                finalAnswer = null;
                turnEmitter.accept(ChatEvent.error("Request messages refused: " + refused));
                return new ActionResult(false, "request messages refused: " + refused);
            }
        }
        // Attached images belong to the human's original message, sent once on this turn's first
        // LLM call. A later step's promptToSend is a tool observation, not that message, so it
        // carries no images of its own.
        List<String> stepImages = (stepCount == 1) ? pendingImages : List.of();

        LlmStepOutcome outcome;
        try {
            outcome = callLlmStep(promptToSend, stepImages);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Agent-loop LLM call failed", e);
            turnError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            turnEmitter.accept(ChatEvent.error("Error: " + e.getMessage()));
            finalAnswer = null;
            return new ActionResult(false, "error: " + e.getMessage());
        }

        // Cancel destroys the call that was in flight, and a destroyed call comes back with no text
        // and no tool call. That is not an empty stop to retry: the retry below would send the same
        // prompt again, so the Cancel the human just pressed would restart the turn instead of
        // ending it.
        if (cancelled) {
            finalAnswer = null;
            return new ActionResult(false, "cancelled");
        }

        // A call the provider could not finish is the turn's failure, whatever it streamed first. It is not an
        // empty stop: a CLI that ended is not sent the same prompt again (TurnWithoutAnAnswerTellsTheAsker_261119_oo01).
        if (outcome.failed()) {
            finalAnswer = null;
            return new ActionResult(false, "error: " + turnError);
        }

        // No tool call, no text and no error is not a final answer — a genuine one always has
        // prose, and a genuine failure sets turnError. This is finish_reason=stop with nothing
        // written, observed on qwen3.8-flash-next-iq3_xxs after a long tool observation: the
        // model's chain of thought ran to a stop token before it ever reached <invoke> or an
        // answer. Retried once, same prompt, as its own step — a second draw from the same model
        // most often lands on a tool call or an answer (EmptyStopRetry_261009_oo01).
        if (!outcome.cutOff() && outcome.calls().isEmpty() && outcome.text().isBlank() && turnError == null) {
            turnEmitter.accept(ChatEvent.thinking(
                    "\n→ retrying: the reply had no text and no tool call\n"));
            stepCount++;
            try {
                outcome = callLlmStep(promptToSend, stepImages);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Agent-loop LLM retry failed", e);
                turnError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                turnEmitter.accept(ChatEvent.error("Error: " + e.getMessage()));
                finalAnswer = null;
                return new ActionResult(false, "error: " + e.getMessage());
            }
        }

        // A Cancel that landed during the retry call ends the turn too, and a tool call the model
        // had already asked for is not run.
        if (cancelled) {
            finalAnswer = null;
            return new ActionResult(false, "cancelled");
        }
        if (outcome.failed()) {
            finalAnswer = null;
            return new ActionResult(false, "error: " + turnError);
        }

        if (outcome.cutOff()) {
            // Neither an answer nor a tool call: the think-cut-off transition decides whether the
            // turn goes on. The step ends here without a final answer so that nothing but it does.
            cutOffPending = true;
            cutOffRun++;
            cutOffChars = outcome.text().length();
            cutOffText.append(outcome.text());
            finalAnswer = null;
            logToConversationSquad("WARNING", "turn" + ioTurnNo + "/step" + stepCount + "/llm cut off at the output limit after "
                    + cutOffChars + " chars (" + cutOffRun + " in a row)");
            return new ActionResult(false, "cut off");
        }
        cutOffRun = 0;
        cutOffText.setLength(0);

        // A reply that asks the person ends the turn, whatever else it calls: the other calls are work the
        // person has not decided on yet, so none of them runs (AskThePersonEndsTheTurn_261119_oo01).
        ToolCall ask = outcome.analysis().calls().stream()
                .filter(tc -> ASK_PERSON.equals(tc.name()) && !questionOf(tc).isBlank())
                .findFirst().orElse(null);
        if (ask != null) {
            repairRun = 0;
            personQuestion = questionOf(ask);
            String prose = TextToolCallParser.stripToolCallBlocks(outcome.text());
            finalAnswer = prose.isBlank() ? personQuestion
                    : prose.contains(personQuestion) ? prose : prose + "\n\n" + personQuestion;
            List<String> dropped = outcome.analysis().calls().stream().filter(tc -> tc != ask).map(ToolCall::name).toList();
            recordAskPerson(dropped);
            turnEmitter.accept(ChatEvent.thinking("\n→ ask_person: the turn ends with a question to the person"
                    + (dropped.isEmpty() ? "" : "; not run: " + String.join(", ", dropped)) + "\n"));
            return new ActionResult(false, "asked the person");
        }

        if (outcome.analysis().broken()) {
            // The reply tried to call a tool, but the call cannot be run as written. Nothing in it runs;
            // the think-repair transition decides whether the turn goes on with a note saying what was
            // wrong. The reply is kept as the answer meanwhile, so a loop without that transition ends
            // the turn as it did before (BrokenToolCallRepair_261115_oo01).
            repairPending = true;
            repairRun++;
            repairText = outcome.text();
            repairProblems = outcome.analysis().problems();
            repairTool = outcome.analysis().exampleTool();
            finalAnswer = outcome.text().trim();
            recordRepair();
            return new ActionResult(false, "tool call could not be read");
        }
        repairRun = 0;

        if (!outcome.calls().isEmpty()) {
            pendingCalls = outcome.calls();
            for (ToolCall tc : outcome.calls()) {
                turnEmitter.accept(ChatEvent.thinking("\n→ " + tc.name() + "(" + tc.argumentsJson() + ")\n"));
            }
            return new ActionResult(true, "action");
        }

        if (outcome.text().isBlank() && turnError != null) {
            finalAnswer = null;
            return new ActionResult(false, "error: " + turnError);
        }
        finalAnswer = outcome.text().trim();
        return new ActionResult(false, "final");
    }

    /**
     * What one {@link #callLlmStep} call produced: the text to keep, the calls to run (none when the reply
     * is a broken call attempt), and what the parser found in it.
     */
    private record LlmStepOutcome(String text, List<ToolCall> calls, boolean cutOff,
                                  TextToolCallParser.Analysis analysis, boolean failed) {}

    /**
     * Records one broken tool call to the I/O log ({@code turn{N}/step{M}/repair}) and the conversation's
     * own log: what was wrong with it, and how many in a row this turn has had.
     */
    private void recordRepair() {
        String what = String.join("; ", repairProblems);
        logToConversationSquad("WARNING", "turn" + ioTurnNo + "/step" + stepCount
                + "/llm: tool call could not be run as written (" + repairRun + " in a row): " + what);
        if (ioLog == null || ioSession < 0) return;
        try {
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/step" + stepCount + "/repair",
                    "BROKEN CALL (" + repairRun + " in a row)\nPROBLEMS:\n- " + String.join("\n- ", repairProblems));
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log repair record failed", e);
        }
    }

    /** @return the {@code question} argument of an {@code ask_person} call, or {@code ""} */
    private static String questionOf(ToolCall call) {
        try {
            return new JSONObject(call.argumentsJson()).optString("question", "").strip();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Records a step that asked the person to the I/O log ({@code turn{N}/step{M}/ask-person}) and the
     * conversation's own log: the question, and the other calls of the same reply, which did not run.
     */
    private void recordAskPerson(List<String> dropped) {
        String notRun = dropped.isEmpty() ? "none" : String.join(", ", dropped);
        logToConversationSquad(dropped.isEmpty() ? "INFO" : "WARNING", "turn" + ioTurnNo + "/step" + stepCount
                + "/llm: ask_person ends the turn; other tool calls in the reply, not run: " + notRun);
        if (ioLog == null || ioSession < 0) return;
        try {
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/step" + stepCount + "/ask-person",
                    "QUESTION: " + personQuestion + "\nNOT RUN: " + notRun);
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log ask-person record failed", e);
        }
    }

    /**
     * The {@code think-asks-person} transition's first step: succeeds only when the step that just ran
     * asked the person with {@code ask_person}, so the turn ends without another model call
     * ({@code AskThePersonEndsTheTurn_261119_oo01}).
     *
     * <p>The question is the turn's answer, and it goes where the answer goes: to the sender of the
     * request the turn works for, or to the screen when the person's own message began the turn. A turn
     * that neither answers a request nor began with the person's message (a timekeeper notice, a job's
     * message) has no one to read it, so in a conversation that is not the liaison and has a superior the
     * question is sent to the superior with {@code ask_chat}; the superior's answer arrives as a new
     * message.</p>
     */
    public ActionResult askedThePerson() {
        if (personQuestion == null) return new ActionResult(false, "did not ask the person");
        if (turnOriginal == null && !startedByThePerson(turnSource) && conversationControls != null) {
            String superior = null;
            try {
                superior = conversationControls.superiorToAsk(projectId, chatId);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not read this conversation's superior", e);
            }
            if (superior != null) {
                String requestId = nextRequestId();
                String sent = AskChatTool.send(system, projectId, chatId, superior,
                        "[question from " + myChatName() + ", asked with ask_person while no request was waiting"
                                + " for its answer]\n" + finalAnswer, requestId);
                logToConversationSquad(sent.startsWith("sent to ") ? "INFO" : "WARNING",
                        "ask_person: the question went to the superior chat-" + superior + ": " + sent);
                if (sent.startsWith("sent to ")) {
                    openRequests.sentWithoutAnOriginal(requestId,
                            ChatUiActorSystem.resolveChatName(projectId, superior), System.currentTimeMillis());
                    publishOutstanding();
                }
            }
        }
        return new ActionResult(true, "asked the person");
    }

    /** @return whether a prompt from {@code source} was written by the person, on the screen or through the API */
    static boolean startedByThePerson(String source) {
        return source == null || source.isBlank() || "human".equals(source) || "screen".equals(source);
    }

    /**
     * Sends one prompt to the provider and returns what came back, recorded to the I/O log as its
     * own {@code turn{N}/step{stepCount}/llm} entry. {@link #stepExpectingAction} calls this once
     * per attempt and increments {@link #stepCount} before each call, so a retry is its own step
     * in the record rather than a second draft overwriting the first.
     *
     * @throws Exception whatever {@code providerRef.ask(...).get()} threw; the caller decides how
     *                    a failed call ends the turn
     */
    private LlmStepOutcome callLlmStep(String promptToSend, List<String> stepImages) throws Exception {
        // The body the provider sends is kept so the I/O log can record it as it was sent
        // (RequestMessagesControl_261018_oo01); a provider with no request body leaves it null.
        java.util.concurrent.atomic.AtomicReference<String> sentBody = new java.util.concurrent.atomic.AtomicReference<>();
        // Any line the provider reads, even one it shows nothing for (a CLI's thinking_tokens), is output: the call
        // is alive. Only a call that produces nothing at all is a stalled one (StalledTurnRecovery).
        ProviderContext ctx = new ProviderContext(apiKey, stepImages, turnNoThink, () -> llmCallProducedOutput = true,
                sentBody::set, true);
        boolean[] cutOff = {false};
        StringBuilder assistantBuf = new StringBuilder();
        // The reasoning is kept for the record, not for the answer: it goes to the I/O log's
        // REASONING: section and never into assistantBuf, which is what the next step parses for
        // tool calls and what becomes the turn's answer.
        StringBuilder thinkingBuf = new StringBuilder();
        // Tool calls the provider's own harness makes inside this one sendPrompt, keyed by
        // tool_use id until their result arrives (CliHarnessProvider_260912_oo01).
        Map<String, ChatEvent> harnessToolUses = new LinkedHashMap<>();
        // Calls the server read from the reply, for this conversation to run (OpenAiCompatStandardToolCalling_261120_oo01).
        List<ToolCall> definedCalls = new java.util.ArrayList<>();
        // Where the response the harness is streaming now begins in assistantBuf: the start of the call, or
        // right after the last harness tool result. A harness that withdraws a response and asks the model
        // to answer again (a "retraction") withdraws the text from here on, and only the answer to the
        // retry is parsed for tool calls (CliHarnessProvider_260912_oo01).
        int[] responseStart = {0};
        ActorRef<LlmProvider> providerRef = providerRef();

        // The ConversationSquad log used to receive nothing between here and recordStepIo() below, so a step
        // spent entirely on a thinking model's reasoning left the System Log tab blank for as long
        // as the model took. Announce the call before making it, then report progress while the
        // reply streams, so the log shows that the step is alive and how far it has got.
        String stepLabel = "turn" + ioTurnNo + "/step" + stepCount + "/llm";
        logToConversationSquad("INFO", stepLabel + " start: model=" + (turnModel == null ? "(server default)" : turnModel)
                + ", promptChars=" + promptToSend.length());
        AtomicLong streamedChars = new AtomicLong();
        AtomicLong progressLoggedAt = new AtomicLong(System.currentTimeMillis());
        StepAnswerStream answerStream = new StepAnswerStream(e -> turnEmitter.accept(e));

        llmCallProducedOutput = false;
        llmCallStartedAt = System.currentTimeMillis();
        java.util.concurrent.CompletableFuture<Object> call = providerRef.ask(p -> {
            // The provider's own "delta"/"result" are not forwarded as they are: this step might
            // still be a tool call, not the final answer. Its streamed text goes to the browser as
            // a provisional answer ("answer_delta"), which StepAnswerStream takes back
            // ("answer_retract", text moved to the live trace) as soon as a tool-call spelling
            // appears. Only finish() emits the browser's "delta"/"result" for the answer, exactly
            // once, with the confirmed final text, and that replaces the provisional text.
            // Forwarding the provider's raw per-step "delta"/"result" would (a) show intermediate
            // steps' raw <invoke> text as if it were the answer, and (b) show the final step's
            // answer text twice (once streamed here, once from finish()) — both observed live
            // before the provisional bubble replaced the raw forwarding.
            Consumer<ChatEvent> wrapped = event -> {
                llmCallProducedOutput = true;
                if ("usage_limit".equals(event.type())) {
                    // Remembered, not shown: the error that follows says it on the screen.
                    usageLimitMessage = event.content() == null ? "usage limit" : event.content();
                    return;
                }
                if ("delta".equals(event.type()) && event.content() != null) {
                    assistantBuf.append(event.content());
                    noteStreamProgress(stepLabel, streamedChars, progressLoggedAt, event.content());
                    answerStream.accept(event.content());
                } else if ("result".equals(event.type())) {
                    // The provider's own per-call completion signal; the agent loop's real
                    // completion signal is finish()'s result event, emitted once for the turn.
                } else if ("tool_call".equals(event.type())) {
                    definedCalls.add(new ToolCall(event.toolUseId(), event.toolName(),
                            event.content() == null ? "" : event.content()));
                } else if ("tool_use".equals(event.type())) {
                    // The harness called one of its own tools. Recorded when its result
                    // arrives, so the log holds the call and its observation as one entry,
                    // the same shape runTool writes for this conversation's own tools.
                    harnessToolUses.put(event.toolUseId(), event);
                    noteStreamProgress(stepLabel, streamedChars, progressLoggedAt, event.content());
                    turnEmitter.accept(ChatEvent.thinking(
                            "\n→ " + event.toolName() + "(" + event.content() + ")\n"));
                } else if ("error".equals(event.type())) {
                    // Remembered as well as relayed: a step that ends with an error and no
                    // text is a failed turn, not an empty answer (TurnErrorInConversation_260913_oo01).
                    turnError = event.content() == null ? "unknown error" : event.content();
                    turnEmitter.accept(event);
                } else if ("cut_off".equals(event.type())) {
                    // Not an error: the provider kept the partial reply and the turn continues
                    // (CutOffReplyContinues_261115_oo01). Remembered here, acted on after the step.
                    cutOff[0] = true;
                    turnEmitter.accept(ChatEvent.thinking("\n→ the reply was cut off at the output limit after "
                            + event.content() + " chars\n"));
                } else if ("retraction".equals(event.type())) {
                    String dropped = assistantBuf.substring(responseStart[0]);
                    assistantBuf.setLength(responseStart[0]);
                    answerStream.discardFrom(responseStart[0]);
                    recordRetraction(event, dropped);
                    turnEmitter.accept(ChatEvent.thinking("\n→ the harness withdrew its last response ("
                            + dropped.length() + " chars) and asked the model to answer again\n"));
                } else if ("tool_result".equals(event.type())) {
                    ChatEvent use = harnessToolUses.remove(event.toolUseId());
                    if (use != null) responseStart[0] = assistantBuf.length();
                    String observation = (event.isError() != null && event.isError())
                            ? "error: " + (event.content() == null ? "" : event.content())
                            : (event.content() == null ? "" : event.content());
                    recordHarnessToolIo(use, observation);
                    String toolName = use == null ? "tool" : use.toolName();
                    turnEmitter.accept(ChatEvent.thinking("Observation (" + toolName + "): "
                            + observation.substring(0, Math.min(200, observation.length())) + "\n"));
                } else {
                    // Reaches here as the provider's own "thinking" event, which is how a
                    // server that separates reasoning (delta.reasoning_content) delivers a
                    // thinking model's chain of thought. Counting it is what makes the log
                    // move during a step that produces no answer text at all.
                    if ("thinking".equals(event.type())) {
                        if (event.content() != null) thinkingBuf.append(event.content());
                        noteStreamProgress(stepLabel, streamedChars, progressLoggedAt, event.content());
                    }
                    turnEmitter.accept(event);
                }
            };
            p.sendPrompt(promptToSend, turnModel, wrapped, ctx);
            return null;
        }, system.getManagedThreadPool());
        boolean failed = false;
        try {
            // Waited for a second at a time, so that a stalled-turn recovery can stop waiting for a call that did not
            // end when it was cancelled (abandonCallForRecovery).
            while (true) {
                try {
                    call.get(1, TimeUnit.SECONDS);
                    break;
                } catch (java.util.concurrent.TimeoutException stillRunning) {
                    if (abandonCall) {
                        turnError = "the model call gave no output and did not end when cancelled; it was abandoned";
                        logToConversationSquad("WARNING", stepLabel + " abandoned by stalled-turn recovery");
                        failed = true;
                        break;
                    }
                }
            }
        } catch (java.util.concurrent.ExecutionException e) {
            // The provider could not finish the call, e.g. its CLI ended without a result. The step fails; what it
            // streamed before stays in the step's record (TurnWithoutAnAnswerTellsTheAsker_261119_oo01).
            Throwable cause = e.getCause() == null ? e : e.getCause();
            turnError = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            turnEmitter.accept(ChatEvent.error("Error: " + turnError));
            logToConversationSquad("WARNING", stepLabel + " failed: " + turnError);
            failed = true;
        } finally {
            llmCallStartedAt = 0;
        }

        String text = assistantBuf.toString();
        // A cut-off reply runs no tool: its last call, if it holds one, never closed, and the text
        // before it is no answer either. The step's text is kept, and shown as the live trace rather
        // than as the answer.
        // A provider that gives the model its tools as definitions has had the server read the calls; the
        // text is not read for them. What a call lacks is said in its result instead (runTool).
        TextToolCallParser.Analysis analysis = cutOff[0] || failed
                ? new TextToolCallParser.Analysis(List.of(), List.of(), "read")
                : byDefinition() ? new TextToolCallParser.Analysis(List.copyOf(definedCalls), List.of(), "read")
                : TextToolCallParser.analyze(text, ChatSession::requiredParameters, this::toolOfThisConversation);
        // A broken call attempt runs none of its calls: one call written wrong among several means the
        // model's plan is not what it meant to send.
        List<ToolCall> calls = analysis.broken() ? List.of() : analysis.calls();
        answerStream.finish(cutOff[0] || failed || !calls.isEmpty() || analysis.broken());
        recordStepIo(promptToSend, sentBody.get(), text, thinkingBuf.toString(), calls);
        turnTranscript.append("You wrote:\n").append(text).append("\n\n");
        return new LlmStepOutcome(text, calls, cutOff[0], analysis, failed);
    }

    /**
     * The session this turn belongs to, for the line the browser shows under the answer.
     *
     * <p>An OpenAI-compatible provider has no session of its own ({@code getSessionId()} returns
     * null), but this product does: the I/O log session is what the Sessions tab keys on, so the
     * identifier under an answer is the one that finds that answer's recorded input and output.
     * Falls back to the provider's own identifier for providers that have one.</p>
     *
     * @return the identifier to report, or {@code null} when there is none
     */
    private String auditSessionId() {
        return ioSession >= 0 ? String.valueOf(ioSession) : provider.getSessionId();
    }

    /**
     * Records what this turn was asked and what it answered, as one entry of its own
     * ({@code turnN/conversation}).
     *
     * <p>The step entries this session already writes cannot serve this purpose. Their
     * {@code REQUEST:} holds the constructed prompt — system prompt, skill catalog, project
     * instructions and the question, concatenated by whichever prompt-construction sub-workflow is
     * installed ({@code PromptConstructionSubworkflow_260826_oo01}). Recovering the bare question
     * from it would mean parsing a shape that a replaced sub-workflow silently changes. The two
     * strings kept here are the same ones handed to {@code collapseTurn}, which is exactly what a
     * later restore needs ({@code ConversationRestoreOnRestart_260904_oo01}).</p>
     *
     * @param askedQuestion the prompt the human sent for this turn
     * @param givenAnswer   the confirmed final answer
     */
    /**
     * Records the turn's id and the request it is or answers ({@code turnN/ids}), so that an id seen
     * in a bubble or a reply header can be searched for in the I/O log and leads to this turn
     * ({@code TurnAndRequestIds_261107_oo01}). Written when the turn starts, so a turn that fails
     * or is paused has it too.
     *
     * @param requestId the request this turn is or answers, or {@code null}
     */
    private void recordTurnIds(String requestId) {
        if (ioLog == null || ioSession < 0) return;
        try {
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/ids",
                    TURN_ID_MARKER + " " + turnId()
                            + (requestId == null ? "" : "\n" + REQUEST_ID_MARKER + " " + requestId));
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log turn id record failed", e);
        }
    }

    /** Marks the turn id in a {@code turnN/ids} entry. */
    static final String TURN_ID_MARKER = "TURN_ID:";
    /** Marks the request id in a {@code turnN/ids} entry. */
    static final String REQUEST_ID_MARKER = "REQUEST_ID:";

    /** The failed-turn counterpart of {@link #recordConversationIo}: the question and the error. */
    private void recordConversationError(String askedQuestion, String error) {
        if (ioLog == null || ioSession < 0) return;
        if (askedQuestion == null || error == null) return;
        try {
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/conversation",
                    CONVERSATION_QUESTION_MARKER + "\n" + askedQuestion
                            + "\n\n" + CONVERSATION_ERROR_MARKER + "\n" + error);
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log conversation error record failed", e);
        }
    }

    private void recordConversationIo(String askedQuestion, String givenAnswer, String model, long durationMs) {
        if (ioLog == null || ioSession < 0) return;
        if (askedQuestion == null || givenAnswer == null) return;
        try {
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/conversation",
                    CONVERSATION_QUESTION_MARKER + "\n" + askedQuestion
                            + "\n\n" + CONVERSATION_ANSWER_MARKER + "\n" + givenAnswer);
            // A separate entry, so the question and answer entry keeps the shape every reader of
            // it expects. What the pane prints under an answer is read from here when the
            // conversation is restored after a restart.
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/answer-meta",
                    "MODEL: " + (model == null ? "" : model) + "\nDURATION_MS: " + durationMs);
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log conversation record failed", e);
        }
    }

    /**
     * Continues the turn numbering of an I/O-log session this conversation is resuming.
     *
     * <p>{@code ioTurn} counts from zero on every start, while a resumed session already holds
     * turns under {@code turn1}, {@code turn2} and so on. Left alone, the first turn after a
     * restart would write its labels on top of the first turn before it, and the Sessions tab would
     * show the two as one ({@code ConversationRestoreOnRestart_260904_oo01}).</p>
     *
     * @param lastRecordedTurn the highest turn number the session already holds; ignored when it is
     *                         not ahead of the counter
     */
    public void resumeTurnNumbering(int lastRecordedTurn) {
        if (lastRecordedTurn > ioTurn) {
            ioTurn = lastRecordedTurn;
        }
    }

    /** Marks the question in a {@code turnN/conversation} entry. */
    public static final String CONVERSATION_QUESTION_MARKER = "QUESTION:";
    /** Marks the answer in a {@code turnN/conversation} entry. */
    public static final String CONVERSATION_ANSWER_MARKER = "ANSWER:";
    /** Marks the failed-turn record: the provider's error where the answer would be. */
    public static final String CONVERSATION_ERROR_MARKER = "ERROR:";

    /** Shortest gap between two streaming-progress lines in one step's ConversationSquad log. */
    private static final long STREAM_PROGRESS_INTERVAL_MS = 1000L;

    /**
     * Adds one streamed chunk to this step's running total and writes a progress line to the ConversationSquad
     * log, at most once per {@link #STREAM_PROGRESS_INTERVAL_MS}. The throttle is what keeps this
     * usable: a chunk is often a single token, and one log line per token would bury every other
     * entry in the System Log tab and make the tab log actor the bottleneck of the turn.
     *
     * <p>Runs on whichever thread the provider streams on, not this actor's thread. That is safe
     * because the counters are atomic and {@code logToConversationSquad} only enqueues into the ConversationSquad log actor's
     * mailbox.</p>
     *
     * @param stepLabel    the step this progress belongs to, e.g. {@code turn3/step2/llm}
     * @param charsSoFar   running character count for this step, across answer and reasoning text
     * @param loggedAt     when a progress line was last written for this step
     * @param chunk        the chunk just received; blank chunks are counted as nothing
     */
    private void noteStreamProgress(String stepLabel, AtomicLong charsSoFar, AtomicLong loggedAt,
                                    String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        long total = charsSoFar.addAndGet(chunk.length());
        long now = System.currentTimeMillis();
        long previous = loggedAt.get();
        if (now - previous < STREAM_PROGRESS_INTERVAL_MS) return;
        // Only the thread that wins the swap writes the line, so two chunks arriving at once do
        // not produce two lines for the same instant.
        if (!loggedAt.compareAndSet(previous, now)) return;
        logToConversationSquad("INFO", stepLabel + " streaming: " + total + " chars");
    }

    /**
     * Records one LLM call of the agent loop to the I/O log ({@code turn{N}/step{M}/llm}), in the
     * same marker format {@link IoLogView}'s {@code trace()} already parses (ported from
     * quarkus-chat-ui3): {@code REQUEST:} is the exact text sent to {@code provider.sendPrompt}
     * (which, on step 1, is the system prompt followed by the user's prompt — see
     * {@code ChatSessionAgentLoop_260823_oo01} "システムプロンプト"), {@code RESPONSE:} is the
     * accumulated reply, {@code REASONING:} is what a thinking model streamed while producing it,
     * {@code TOOL_CALLS:} lists any tool-call requests found in it.
     *
     * <p>The reasoning is recorded here and nowhere else. It is deliberately not part of the
     * answer — it never enters {@code assistantBuf}, is not sent back to the model, and is not
     * kept in the conversation — so without this section the only copy would be the one the
     * browser happened to be drawing, and closing the tab would destroy it. Recording every input
     * and output of the model is what this product is for ({@code Overview_260712_oo01}), and the
     * section name matches the one {@link #recordTurnIo} already writes, so {@code IoLogView}'s
     * existing parse covers both.</p>
     */
    private void recordStepIo(String promptSent, String responseText, String thinking,
                              List<ToolCall> calls) {
        recordStepIo(promptSent, null, responseText, thinking, calls);
    }

    /**
     * @param requestBody the body the provider sent, or null for a provider that builds none
     *                    (a CLI harness), in which case the request is reconstructed from
     *                    {@code promptSent} as a single user message
     */
    private void recordStepIo(String promptSent, String requestBody, String responseText, String thinking,
                              List<ToolCall> calls) {
        if (ioLog == null || ioSession < 0) return;
        try {
            String requestJson = requestBody != null
                    ? RequestBodyRecord.forLog(requestBody)
                    : new org.json.JSONObject()
                            .put("messages", new org.json.JSONArray().put(
                                    new org.json.JSONObject().put("role", "user").put("content", promptSent)))
                            .toString();
            StringBuilder m = new StringBuilder();
            m.append("REQUEST:\n").append(requestJson);
            m.append("\n\nRESPONSE:\n").append(responseText == null ? "" : responseText);
            if (thinking != null && !thinking.isBlank()) {
                m.append("\n\nREASONING:\n").append(thinking);
            }
            if (!calls.isEmpty()) {
                m.append("\n\nTOOL_CALLS:\n");
                for (ToolCall tc : calls) {
                    m.append("  ").append(tc.name()).append(" ").append(tc.argumentsJson()).append("\n");
                }
            }
            if (turnError != null) {
                m.append("\n\nERROR:\n").append(turnError);
            }
            m.append("\n\nUSAGE: promptTokens=0 completionTokens=0");
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/step" + stepCount + "/llm", m.toString());
            logToConversationSquad("INFO", "turn" + ioTurnNo + "/step" + stepCount + "/llm done: "
                    + (responseText == null ? 0 : responseText.length()) + " chars, "
                    + calls.size() + " tool call(s)");
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log step record failed", e);
        }
    }

    /**
     * The step-limit transition's guard: succeeds once this turn has used its steps, so the
     * workflow moves to its end state; fails while steps remain, so the scan falls through to the
     * transition that actually calls the LLM ({@code TurnResourceLimits_260830_oo01}). Makes no
     * LLM call either way.
     *
     * @param limit the workflow's step limit; blank or unparsable falls back to
     *              {@link #DEFAULT_STEP_LIMIT}
     * @return {@link ActionResult} with {@code success=true} iff the limit has been reached
     */
    public ActionResult stepLimitReached(String limit) {
        int max = parsePositiveOr(limit, DEFAULT_STEP_LIMIT);
        boolean reached = stepCount >= max;
        if (reached) stepLimitHit = true;
        if (reached && finalAnswer == null) {
            // A turn that ran out of steps right after a cut-off, or a broken tool call, still keeps what
            // that reply wrote.
            String kept = cutOffText.length() > 0 ? cutOffText.toString().strip()
                    : (repairText == null ? "" : repairText.strip());
            finalAnswer = (kept.isEmpty() ? "" : kept + "\n\n") + "(step limit of " + max + " reached)";
        }
        return new ActionResult(reached, reached ? "step limit " + max + " reached"
                                                 : stepCount + "/" + max + " steps used");
    }

    /** @param text a workflow argument; @param fallback used when it is blank or not a positive number */
    private static int parsePositiveOr(String text, int fallback) {
        if (text == null || text.isBlank()) return fallback;
        try {
            int v = Integer.parseInt(text.strip());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Which tools this conversation is shown and may call ({@code CliHarnessProvider_260912_oo01}).
     * A provider whose harness runs its own file and shell tools gets {@link ToolSet#HARNESS}
     * so the same tools are not offered twice under different names.
     *
     * @param toolSet the tool set; {@code null} keeps the current one
     */
    public void setToolSet(ToolSet toolSet) {
        if (toolSet != null) this.toolSet = toolSet;
        renewToolRunner();
    }

    /** @return which tools this conversation is shown and may call */
    public ToolSet getToolSet() { return toolSet; }

    /**
     * The tools plugin jars added to this instance ({@code ProviderAndToolPlugins_260912_oo01}).
     * Listed after the built-in tools in the first prompt and executed like them; the generating
     * side sets this once from the registry.
     *
     * @param tools the plugin tools, in the order they are listed
     */
    public void setPluginTools(List<ConversationTool> tools) {
        pluginTools.clear();
        for (ConversationTool t : tools) pluginTools.put(t.name(), t);
        renewToolRunner();
    }

    /**
     * Copies what the tools that do not drive the conversation read into a new {@link ConversationToolRunner}, and
     * hands it to the controls for chat-ui's MCP server ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}).
     */
    private void renewToolRunner() {
        toolRunner = new ConversationToolRunner(system, projectId, chatId, fileScope, conversationControls,
                roleTableRef, skillRegistryRef, pluginTools, toolSet, calculator, ioLog,
                () -> new ConversationToolRunner.IoPosition(ioSession, ioTurnNo, stepCount));
        if (conversationControls != null && projectId != null && chatId != null) {
            conversationControls.publishToolRunner(toolRunner);
        }
    }

    /** @return the runner of the tools that do not drive this conversation */
    private ConversationToolRunner toolRunner() {
        if (toolRunner == null) renewToolRunner();
        return toolRunner;
    }

    /** @return whether the named tool is a plugin tool whose observation is a set of pages */
    private boolean returnsPages(String toolName) {
        ConversationTool t = pluginTools.get(toolName);
        return t != null && t.returnsPages();
    }

    /** @param maxObservationChars ceiling on what a workflow may ask to keep of one observation */
    public void setMaxObservationChars(int maxObservationChars) {
        if (maxObservationChars > 0) this.maxObservationChars = maxObservationChars;
    }

    /**
     * Executes every pending tool call, appends the observations to the scratchpad prompt for
     * the next {@code stepExpectingAction()} call, then clears {@code pendingCalls}.
     *
     * @return {@link ActionResult} with {@code success=true} (this transition never fails)
     */
    public ActionResult runTool() {
        return runTool("");
    }

    /**
     * @param observationChars how much of each observation the model may see, as the workflow
     *                         wrote it; blank falls back to {@link ContextBudget#OBS_THRESHOLD},
     *                         and any value is capped at this session's ceiling
     * @return {@link ActionResult} with {@code success=true} (this transition never fails)
     */
    public ActionResult runTool(String observationChars) {
        return runTool(observationChars, false);
    }

    /**
     * The same as {@link #runTool(String)} but summarising each fetched web page rather than
     * keeping its opening characters. Fails — so the workflow falls through to {@code run-tool} —
     * when this turn called no {@code web_search}, or when the provider cannot make a completion
     * outside the conversation.
     *
     * <p>A transition of its own rather than a flag, because which policy fits an observation is
     * the workflow's decision, and the workflow already chooses among transitions this way
     * ({@code think-action} → {@code think-continue} → {@code think-final}).
     *
     * @param observationChars as in {@link #runTool(String)}
     * @return {@code success=false} when this policy does not apply to what was called
     */
    public ActionResult runToolSummarizingPages(String observationChars) {
        if (pendingCalls == null || pendingCalls.stream().noneMatch(tc -> returnsPages(tc.name()))) {
            return new ActionResult(false, "no page-returning tool call to summarise");
        }
        if (!(provider instanceof com.scivicslab.chatui.openaicompat.OpenAiCompatProvider)) {
            return new ActionResult(false, "provider cannot complete outside the conversation");
        }
        return runTool(observationChars, true);
    }

    private ActionResult runTool(String observationChars, boolean summarizePages) {
        if (pendingCalls == null || pendingCalls.isEmpty()) {
            return new ActionResult(true, "observed");
        }
        StringBuilder observations = new StringBuilder();
        List<LlmProvider.ToolCallResult> results = new java.util.ArrayList<>();
        for (ToolCall tc : pendingCalls) {
            String fullObservation;
            // Recorded here unless chat-ui's MCP server ran it, which records it itself: once either way.
            boolean recordHere = true;
            try {
                String refusal = byDefinition() ? whyNotRunnable(tc) : null;
                if (refusal != null) {
                    fullObservation = refusal;
                } else if (byDefinition() && !drivesConversation(tc.name())
                        && !(summarizePages && returnsPages(tc.name()))) {
                    turnToolCalls++;
                    fullObservation = providerRef().ask(p -> p.callTool(tc.name(), tc.argumentsJson()),
                            system.getManagedThreadPool()).get();
                    if (fullObservation == null) fullObservation = "error: the provider runs no tools";
                    // An external MCP server does not write this I/O log (ExternalMcpServersOfAConversation_261120_oo01).
                    recordHere = isExternalTool(tc.name());
                } else {
                    fullObservation = executeTool(tc, summarizePages);
                }
            } catch (Exception e) {
                fullObservation = "error: " + e.getMessage();
            }
            String fitted = (summarizePages && returnsPages(tc.name()))
                    ? pageSummarizer().summarize(fullObservation)
                    : fullObservation;
            String forModel = ContextBudget.truncateObservation(fitted,
                    Math.min(parsePositiveOr(observationChars, ContextBudget.OBS_THRESHOLD),
                             maxObservationChars));
            if (recordHere) recordToolIo(tc, fullObservation);
            results.add(new LlmProvider.ToolCallResult(tc.id(), tc.name(), forModel));
            turnEmitter.accept(ChatEvent.thinking("Observation (" + tc.name() + "): "
                    + forModel.substring(0, Math.min(200, forModel.length())) + "\n"));
            observations.append("Tool result (").append(tc.name()).append("):\n")
                    .append(forModel).append("\n\n");
        }
        pendingCalls = null;
        pendingPrompt = observations.toString().stripTrailing();
        if (byDefinition()) {
            // The results go back as tool messages under each call's id; the next step adds no user
            // message (OpenAiCompatStandardToolCalling_261120_oo01).
            try {
                providerRef().ask(p -> { p.addToolResults(results); return null; }, system.getManagedThreadPool()).get();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not give the provider the tool results", e);
            }
            pendingPrompt = "";
        }
        // Kept as soon as the tools have run, not when the next step sends them: a turn paused right
        // after a tool finishes has no next step, and what the tool did would be lost
        // (TimekeeperPausesATurnForAnUrgentRequest_261107_oo01).
        turnTranscript.append(observations);
        return new ActionResult(true, "observed");
    }

    /**
     * Records one tool call to the I/O log ({@code turn{N}/step{M}/tool}), full untruncated
     * observation included (the copy fed back to the model is truncated by
     * {@link ContextBudget#truncateObservation}, but the log keeps the whole thing).
     */
    private void recordToolIo(ToolCall tc, String fullObservation) {
        if (ioLog == null || ioSession < 0) return;
        try {
            String m = "TOOL: " + tc.name() + "\nINPUT:\n" + tc.argumentsJson()
                    + "\nOBSERVATION:\n" + fullObservation;
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/step" + stepCount + "/tool", m);
            logToConversationSquad("INFO", "turn" + ioTurnNo + "/step" + stepCount + "/tool: " + tc.name());
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log tool record failed", e);
        }
    }

    /**
     * Records a response the provider's harness withdrew ({@code turn{N}/step{M}/retraction}): the text it had
     * streamed, whole and untouched, and the ids of the messages it named. The step's own {@code llm} entry
     * holds only the answer to the retry, which is what was parsed for tool calls.
     *
     * @param event   the retraction event
     * @param dropped the text streamed for the withdrawn response
     */
    private void recordRetraction(ChatEvent event, String dropped) {
        if (ioLog == null || ioSession < 0) return;
        try {
            String m = "RETRACTED: the harness withdrew this response and asked the model to answer again\n"
                    + "MESSAGES: " + (event.content() == null ? "(none named)" : event.content())
                    + "\nTEXT:\n" + dropped;
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/step" + stepCount + "/retraction", m);
            logToConversationSquad("INFO", "turn" + ioTurnNo + "/step" + stepCount + "/retraction: "
                    + dropped.length() + " chars dropped");
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log retraction record failed", e);
        }
    }

    /**
     * Records one tool call the provider's own harness made inside a sendPrompt
     * ({@code CliHarnessProvider_260912_oo01}) to the I/O log, under the same
     * {@code turn{N}/step{M}/tool} label and {@code TOOL:/INPUT:/OBSERVATION:} form
     * {@link #recordToolIo} uses, so {@link IoLogView}'s trace lists it as one more tool step.
     *
     * @param use         the tool_use event the result answers, or {@code null} if none was seen
     * @param observation the harness's result text, whole; prefixed {@code error: } on failure
     */
    private void recordHarnessToolIo(ChatEvent use, String observation) {
        if (ioLog == null || ioSession < 0) return;
        String toolName = use == null ? "(unknown harness tool)" : use.toolName();
        String input = (use == null || use.content() == null) ? "{}" : use.content();
        try {
            String m = "TOOL: " + toolName + "\nINPUT:\n" + input + "\nOBSERVATION:\n" + observation;
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/step" + stepCount + "/tool", m);
            logToConversationSquad("INFO", "turn" + ioTurnNo + "/step" + stepCount + "/tool (harness): " + toolName);
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log harness tool record failed", e);
        }
    }

    /**
     * Emits the final answer (if any), commits it to history, stores it under the turn's
     * {@code resultKey} if any, and runs the same completion hand-off as {@link #onPromptComplete}.
     *
     * @return {@link ActionResult} with {@code success=true}
     */
    public ActionResult finish() {
        // For an agent loop without the step: the reply this turn began with is settled before the turn ends.
        decideReplyAtTurnEnd();
        if (!cancelled && finalAnswer == null && turnError != null) {
            // A failed turn (TurnErrorInConversation_260913_oo01): the error was relayed live as it
            // happened; here it becomes part of the record. Not an assistant answer — the provider
            // never gets it back as something the model said — and no empty answer bubble.
            recordHistory("error", "Error: " + turnError);
            recordConversationError(question, turnError);
            // The asker is told why there is no answer, not only that there is none.
            String why = "its turn failed: " + turnError;
            turnAnswer = new TurnReply(null, why, turnId());
            if (activeResultKey != null) completedEndings.put(activeResultKey, why);
            turnEmitter.accept(ChatEvent.result(auditSessionId(), 0.0,
                    System.currentTimeMillis() - turnStartedAt, provider.getCurrentModel(), false)
                    .withTurn(turnId(), turnRequestId));
        }
        if (!cancelled && finalAnswer != null && answerBeforeDecision != null && !answerBeforeDecision.isBlank()) {
            finalAnswer = answerBeforeDecision + "\n\n" + finalAnswer;
        }
        answerBeforeDecision = null;
        if (!cancelled && finalAnswer != null && finalAnswer.isBlank()) {
            // Nothing written, even after the retry: there is no answer to give. The asker is told so when the turn
            // closes, rather than sent an empty reply that reads as answered (TurnWithoutAnAnswerTellsTheAsker_261119_oo01).
            finalAnswer = null;
            logToConversationSquad("WARNING", "turn" + ioTurnNo + " ended without an answer");
            turnEmitter.accept(ChatEvent.result(auditSessionId(), 0.0,
                    System.currentTimeMillis() - turnStartedAt, provider.getCurrentModel(), false)
                    .withTurn(turnId(), turnRequestId));
        }
        if (!cancelled && finalAnswer != null) {
            String answer = finalAnswer;
            long answeredIn = System.currentTimeMillis() - turnStartedAt;
            recordHistory("assistant", answer, null, provider.getCurrentModel(), answeredIn);
            recordConversationIo(question, answer, provider.getCurrentModel(), answeredIn);
            String ending = ChatInspection.endingLine(
                    personQuestion != null ? ChatInspection.ENDED_ASKED
                            : stepLimitHit ? ChatInspection.ENDED_STEP_LIMIT : ChatInspection.ENDED_ANSWERED,
                    stepCount, turnToolCalls);
            turnAnswer = new TurnReply(answer, ending, turnId());
            if (activeResultKey != null) {
                storeCompletedResult(activeResultKey, answer);
                completedEndings.put(activeResultKey, ending);
            }
            turnEmitter.accept(ChatEvent.delta(answer));
            // The browser puts what this event carries under the answer, as quarkus-chat-ui does.
            // Cost stays 0: a local model bills nothing, and the browser hides the field unless it
            // is above zero, rather than showing a made-up figure.
            turnEmitter.accept(ChatEvent.result(auditSessionId(), 0.0,
                    System.currentTimeMillis() - turnStartedAt, provider.getCurrentModel(), false)
                    .withTurn(turnId(), turnRequestId));
        }
        // The turn's step-by-step messages — each carrying that step's whole observation — are of
        // no further use once the answer exists, and they crowd out earlier turns in the provider's
        // fixed-size history (TurnResourceLimits_260830_oo01). Replace them with what a later turn
        // actually needs: what was asked, and what was answered.
        try {
            String restore = requestMessagesToRestore;
            requestMessagesToRestore = null;
            if (restore != null) providerRef().tell(p -> p.replaceRequestMessages(restore));
            if (pauseRequested && finalAnswer == null) {
                // Paused, not finished: what the turn did so far stands as its answer in the history,
                // so the turn that resumes the work reads it, and the urgent request's turn in between
                // does not wipe it out when its own steps are collapsed.
                String progress = "[This turn was paused before it finished, so that an urgent request could "
                        + "be answered. What it had done so far:]\n\n" + turnTranscript;
                providerRef().tell(p -> p.collapseTurn(question, progress));
                recordHistory("assistant", "(paused for an urgent request; the work resumes after it)");
            } else {
                providerRef().tell(p -> p.collapseTurn(question, finalAnswer));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not collapse this turn's provider history", e);
        }
        onPromptComplete(turnEmitter, turnDone, turnSelf);
        return new ActionResult(true, "finished");
    }

    // ---- Babysitter loop (BabysitterLoopWorkflowShape_260828_oo01, made phase-agnostic by
    // GenericBabysitterPhases_260829_oo01). Each phase is the same shape — ask the worker, judge
    // the reply, redo it if the judgment says so — so what differs between phases (the instruction
    // to send, the criteria to judge against) arrives as the action's YAML `arguments`, not baked
    // into a method name. Every action runs execution: direct, for the same reason as the base
    // agent loop (see chat-session-agent-loop.yaml's own note).

    /**
     * Entry point of a phase: resolves this conversation's {@code "worker"} collaborator via
     * its project's {@link RoleTable} and sends it {@code instruction}. Resets the redo budget, so each
     * phase gets its own (a phase that used up its redos does not starve the next one).
     *
     * @param instruction what to ask the worker to do in this phase
     * @return {@link ActionResult} with {@code success=true} iff a worker was resolved and replied
     */
    public ActionResult requestFromWorker(String instruction) {
        redoCount = 0;
        redoNote = null;
        RoleTable.Collaborator worker = resolveWorker();
        if (worker == null) {
            lastCollaborationError = "no collaborator with role 'worker' set for " + myChatName()
                    + " (use set_collaborator first)";
            return new ActionResult(false, lastCollaborationError);
        }
        String workerChatId = worker.name();
        String prompt = (instruction == null || instruction.isBlank()) ? pendingPrompt : instruction;
        String reply = askWorker(worker, prompt);
        recordWorkerRequestIo(workerChatId, prompt, reply);
        if (reply == null || reply.startsWith("error:")) {
            lastCollaborationError = reply != null ? reply : "no reply from worker";
            return new ActionResult(false, lastCollaborationError);
        }
        lastWorkerReply = reply;
        return new ActionResult(true, "reply received");
    }

    /**
     * One LLM call judging {@link #lastWorkerReply} against {@code criteria}. Does not finish the
     * turn even when it accepts — reaching {@code "end"} is the state machine's job, so a phase
     * that is accepted can simply move on to the next one.
     *
     * @param criteria what makes the reply good enough in this phase
     * @return {@link ActionResult} with {@code success=true} iff the reply was judged acceptable
     */
    public ActionResult judgeResult(String criteria) {
        lastJudgment = callJudgeLlm(criteria, lastWorkerReply);
        boolean acceptable = lastJudgment.trim().toUpperCase(java.util.Locale.ROOT).startsWith("ACCEPT");
        if (acceptable) finalAnswer = lastWorkerReply;
        return new ActionResult(acceptable, acceptable ? "accepted" : "needs work");
    }

    /**
     * Reached only once {@link #judgeResult} has just failed. Cheap check, no LLM call. On reaching
     * the limit, keeps the best reply so far as the turn's answer for {@code finish()} to emit.
     *
     * @return {@link ActionResult} with {@code success=true} iff this phase's redo budget is exhausted
     */
    public ActionResult retryLimitReached(String limit) {
        boolean reached = redoCount >= parsePositiveOr(limit, DEFAULT_REDO_LIMIT);
        if (reached) finalAnswer = lastWorkerReply;
        return new ActionResult(reached, reached ? "redo limit reached" : "budget remains");
    }

    /**
     * Reached only once {@link #judgeResult} and {@link #retryLimitReached} have both just failed —
     * always succeeds (the judge state's catch-all). Takes what to fix from the judgment already
     * captured by {@link #judgeResult}; makes no LLM call of its own.
     *
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult judgeNeedsRedo() {
        redoNote = lastJudgment;
        return new ActionResult(true, "redo requested");
    }

    /**
     * Sends the judgment's specifics back to the worker. Re-resolves the {@code "worker"} role
     * rather than reusing what {@link #requestFromWorker} found, so a mid-flow {@code
     * set_collaborator} reassignment takes effect ({@code CollaborationGraph_260828_oo01}).
     *
     * @return {@link ActionResult} with {@code success=true} iff the worker replied
     */
    public ActionResult requestRedo() {
        RoleTable.Collaborator worker = resolveWorker();
        if (worker == null) {
            lastCollaborationError = "no collaborator with role 'worker' set for " + myChatName()
                    + " (use set_collaborator first)";
            return new ActionResult(false, lastCollaborationError);
        }
        String workerChatId = worker.name();
        String prompt = "Please revise your previous answer to address this feedback:\n" + redoNote;
        String reply = askWorker(worker, prompt);
        recordWorkerRequestIo(workerChatId, prompt, reply);
        if (reply == null || reply.startsWith("error:")) {
            lastCollaborationError = reply != null ? reply : "no reply from worker";
            return new ActionResult(false, lastCollaborationError);
        }
        lastWorkerReply = reply;
        redoCount++;
        return new ActionResult(true, "revised reply received");
    }

    /**
     * Fallback for every phase entry and redo state: reached only when {@link #requestFromWorker}
     * or {@link #requestRedo} has just failed (no worker resolved, or it didn't reply). Without it
     * those states would have no remaining transition to try and the turn would stay {@code busy}
     * forever ({@code BabysitterLoopWorkflowShape_260828_oo01} 追記).
     *
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult reportCollaborationFailure() {
        finalAnswer = "(babysitter loop stopped: " + lastCollaborationError + ")";
        finish();
        return new ActionResult(true, "reported failure");
    }

    /** @return the last reply a worker gave this conversation's agent loop, or {@code null} */
    public String lastWorkerReply() {
        return lastWorkerReply;
    }

    /**
     * Asks the worker and waits for its reply: on this instance as {@code ask_chat}'s plan path does, on another one over
     * the remote path when the role names a node ({@code set_collaborator} with {@code node}).
     */
    private String askWorker(RoleTable.Collaborator worker, String prompt) {
        return worker.node() == null
                ? AskChatTool.askQualified(system, watchdogRef, myChatName(), worker.name(), prompt,
                        WORKER_WAIT_TIMEOUT_SECONDS, nextRequestId())
                : AskChatTool.askRemoteQualified(system, myChatName(), worker.node(), worker.name(), prompt,
                        WORKER_WAIT_TIMEOUT_SECONDS, nextRequestId());
    }

    /** Resolves this ConversationSquad's {@code "worker"} collaborator via its project's {@link RoleTable}, or {@code null}. */
    private RoleTable.Collaborator resolveWorker() {
        if (roleTableRef == null) return null;
        try {
            return roleTableRef.ask(t -> t.collaboratorAt(myChatName(), "worker")).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.log(Level.WARNING, "resolveWorker: role table lookup failed", e);
            return null;
        }
    }

    /**
     * One-shot blocking LLM call judging {@code reply} against {@code criteria}: answers {@code
     * ACCEPT} if it is good enough, or {@code REVISE: ...} otherwise. Streams to {@code turnEmitter} as
     * "thinking" (same treatment as {@link #stepExpectingAction()}'s own LLM call).
     */
    private String callJudgeLlm(String criteria, String reply) {
        String prompt = "Judge the text below against these criteria:\n" + criteria
                + "\n\nIf it meets them as-is, reply with exactly:\nACCEPT\nOtherwise, reply "
                + "with:\nREVISE: <a concise, specific description of what to fix>\n\nText:\n" + reply;
        String verdict;
        try {
            verdict = callModelOnce(prompt, turnNoThink);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Babysitter judge LLM call failed", e);
            String failed = "REVISE: judge call failed: " + e.getMessage();
            recordJudgeIo(prompt, failed);
            return failed;
        }
        recordJudgeIo(prompt, verdict);
        return verdict;
    }

    /**
     * One blocking call to this conversation's model, whose streamed text is shown as "thinking" like the agent
     * loop's own calls.
     *
     * @param prompt  what to ask
     * @param noThink whether to ask the model to skip its thinking phase
     * @return the model's reply text
     * @throws Exception when the call fails
     */
    private String callModelOnce(String prompt, boolean noThink) throws Exception {
        StringBuilder buf = new StringBuilder();
        ProviderContext ctx = new ProviderContext(apiKey, List.of(), noThink, () -> {});
        providerRef().ask(p -> {
            Consumer<ChatEvent> wrapped = event -> {
                if ("delta".equals(event.type()) && event.content() != null) {
                    buf.append(event.content());
                    turnEmitter.accept(ChatEvent.thinking(event.content()));
                }
            };
            p.sendPrompt(prompt, turnModel, wrapped, ctx);
            return null;
        }, system.getManagedThreadPool()).get();
        return buf.toString();
    }

    /**
     * Whether the end-of-turn step runs. On unless {@code -Dchat-ui.reminder-decision=false}, which turns the
     * model call off for an instance that must not pay it (the test suite does, except where it tests the step).
     */
    private volatile boolean reminderDecisionEnabled =
            !"false".equalsIgnoreCase(System.getProperty("chat-ui.reminder-decision", "true"));

    /** @param enabled whether the end-of-turn step runs on this conversation */
    public void setReminderDecisionEnabled(boolean enabled) {
        this.reminderDecisionEnabled = enabled;
    }

    /** What the end-of-turn reply-watch step did last, one line per open request; empty before it first runs. */
    private volatile List<String> lastReplyWatchLines = List.of();

    /** @return what the end-of-turn reply-watch step did last, one line per open request */
    public List<String> lastReplyWatchLines() {
        return lastReplyWatchLines;
    }

    /**
     * Agent-loop action at the end of every turn, whatever ended it: arms again the timekeeper's reply-wait watch of
     * each request this conversation sent that is still open (no reply, not cancelled, not closed), so a request that
     * stays open is reminded once per interval until it closes, instead of once and never again. The decision is the
     * workflow's, not the model's ({@code ReplyWatchRearmedAtTheEndOfEveryTurn_261116_oo01}). Each request's outcome
     * is logged.
     *
     * @return always {@code success=true}: a failure here must not stop the turn from ending
     */
    public ActionResult rearmReplyWatches() {
        try {
            List<OpenRequests.Outstanding> open = openRequests.outstandingChildren();
            ActorRef<Timekeeper> keeper = system == null ? null : system.getActor(myChatName() + ".timekeeper");
            if (keeper == null) {
                lastReplyWatchLines = List.of();
                if (!open.isEmpty()) logToConversationSquad("WARNING", "turn" + ioTurnNo + ": reply watch: no timekeeper");
                return new ActionResult(true, "no timekeeper");
            }
            long now = System.currentTimeMillis();
            List<String> lines = keeper.ask(k -> k.rearmReplyWatches(open, now)).get(5, TimeUnit.SECONDS);
            lastReplyWatchLines = lines;
            for (String line : lines) logToConversationSquad("INFO", "turn" + ioTurnNo + ": " + line);
            return new ActionResult(true, lines.size() + " open request(s)");
        } catch (Exception e) {
            logger.log(Level.WARNING, "The end-of-turn reply watch failed", e);
            logToConversationSquad("WARNING", "turn" + ioTurnNo + ": reply watch failed: " + e.getMessage());
            return new ActionResult(true, "reply watch failed");
        }
    }

    /** What the end-of-turn step decided last, for the record and for tests; {@code null} before the first turn ends. */
    private volatile String lastReminderDecision;

    /** @return the line the end-of-turn step last wrote to the log, or {@code null} */
    public String lastReminderDecision() {
        return lastReminderDecision;
    }

    /**
     * Agent-loop action at the end of a turn, after the final answer is fixed and before the turn ends: decides
     * whether the timekeeper must remind this conversation, so that a promised later report does not depend on
     * the model remembering to register one ({@code ReminderDecidedAtTheEndOfTheTurn_261114_oo01}).
     *
     * <ul>
     * <li>Requests this conversation sent for a request it received are still out: the timekeeper's child-reply watch
     * covers them. No model call; the log says so.</li>
     * <li>Otherwise one small model call must answer {@code REGISTER <minutes or DEFAULT> | "<quote>"} or
     * {@code NONE | <reason>}. Both are logged. On {@code REGISTER}, a reminder is registered with this
     * conversation's timekeeper.</li>
     * </ul>
     *
     * @return always {@code success=true}: a failure here must not stop the turn from ending
     */
    public ActionResult decideReminder() {
        // A turn that ends with a question promises nothing: the answer comes back as a new message.
        if (personQuestion != null) {
            noteReminderDecision("NONE | the turn ended with a question to the person (ask_person)", null, null);
            return new ActionResult(true, "asked the person");
        }
        try {
            decideReminderNow();
        } catch (Exception e) {
            logger.log(Level.WARNING, "The end-of-turn reminder decision failed", e);
            noteReminderDecision("SKIPPED | the decision failed: " + e.getMessage(), null, null);
        }
        return new ActionResult(true, "reminder decided");
    }

    private void decideReminderNow() throws Exception {
        if (!reminderDecisionEnabled) return;
        if (cancelled || pauseRequested || turnError != null || finalAnswer == null || finalAnswer.isBlank()) {
            noteReminderDecision("SKIPPED | the turn has no final answer", null, null);
            return;
        }
        List<OpenRequests.Outstanding> out = openRequests.outstandingChildren();
        if (!out.isEmpty()) {
            noteReminderDecision("COVERED | covered by child-reply watch (" + out.size() + " request(s) still out: "
                    + out.stream().map(OpenRequests.Outstanding::requestId).collect(java.util.stream.Collectors.joining(", "))
                    + ")", null, null);
            return;
        }
        // Who the answer was written to: the conversation that asked, or the person.
        FollowUpTo reminded = turnReminder != null && turnFollowUp != null && !REMINDER_TO_SCREEN.equals(turnFollowUp.asker())
                ? turnFollowUp : null;
        String request = reminded != null ? turnReminder : turnOriginal;
        String asker = reminded != null ? reminded.asker() : turnOriginal != null ? openRequests.askerOf(turnOriginal) : null;
        if (asker == null) request = null;
        String addressee = asker == null ? "the person" : asker;
        String now = java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE) z"));
        // What finish() will emit: the text written before a decision was asked for, then the final step's.
        String answerText = answerBeforeDecision != null && !answerBeforeDecision.isBlank()
                ? answerBeforeDecision + "\n\n" + finalAnswer : finalAnswer;
        String prompt = ReminderDecision.prompt(answerText, addressee, AskChatTool.unansweredFrom(myChatName()), now);
        String reply;
        try {
            reply = callModelOnce(prompt, true);
        } catch (Exception e) {
            noteReminderDecision("SKIPPED | the model call failed: " + e.getMessage(), prompt, null);
            return;
        }
        ReminderDecision decision = ReminderDecision.parse(reply, answerText);
        if (decision.kind() != ReminderDecision.Kind.REGISTER) {
            noteReminderDecision(decision.describe(), prompt, reply);
            return;
        }
        long minutes = decision.minutes() != null ? decision.minutes() : Timekeeper.DEFAULT_PROMISE_REMINDER_MINUTES;
        ActorRef<Timekeeper> keeper = system == null ? null : system.getActor(myChatName() + ".timekeeper");
        if (keeper == null) {
            noteReminderDecision("SKIPPED | no timekeeper to register with | " + decision.describe(), prompt, reply);
            return;
        }
        String req = request;
        String ask = asker;
        // An asker on another instance is reached through the link its request carried, recorded with the promise.
        OpenRequests.RemoteLink link = request == null ? null
                : reminded != null ? reminded.link() : openRequests.linkOf(request);
        int number = keeper.ask(k -> k.expectReport(req, ask, minutes, link)).get(5, TimeUnit.SECONDS);
        noteReminderDecision("REGISTERED " + minutes + " min (reminder " + number + ") for "
                + (request == null ? "the person" : "request " + request + " from " + asker) + " | \""
                + decision.quote() + "\""
                + (decision.minutes() == null ? " | no time in the answer: the default" : ""), prompt, reply);
    }

    /**
     * Writes the end-of-turn decision where a person can find it: the conversation's log, the screen's thinking
     * trace, and the turn's record, as a step of its own that is not mistaken for the answer.
     *
     * @param decision what was decided, as one line
     * @param prompt   the question put to the model, or {@code null} when none was
     * @param reply    the model's reply, or {@code null} when none was asked
     */
    private void noteReminderDecision(String decision, String prompt, String reply) {
        lastReminderDecision = decision;
        logToConversationSquad("INFO", "turn" + ioTurnNo + ": reminder decision: " + decision);
        turnEmitter.accept(ChatEvent.thinking("\n→ reminder: " + decision + "\n"));
        if (prompt != null) stepCount++;   // a model call was made: it is counted like the others
        if (ioLog == null || ioSession < 0) return;
        try {
            String m = "TOOL: reminder_decision\nINPUT:\n" + (prompt == null ? "(no model call)" : prompt)
                    + "\nOBSERVATION:\n" + (reply == null ? "" : "MODEL REPLY: " + reply + "\n") + "DECISION: " + decision;
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/step" + stepCount + "/tool", m);
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log reminder decision record failed", e);
        }
    }

    /**
     * Records one request to the worker as this turn's next tool step
     * ({@code SupervisorTurnInTheRecord_260913_oo01}). Without it, a supervising turn wrote only
     * its {@code turnN/conversation} row, so the turn appeared in no list and its trace could not
     * be opened — the judgement that accepted the worker's text was in nobody's record.
     *
     * @param workerChatName the worker's full name, as the {@link RoleTable} holds it
     * @param prompt         what was sent to it
     * @param reply          what came back, or an {@code error:} line when nothing did
     */
    private void recordWorkerRequestIo(String workerChatName, String prompt, String reply) {
        if (ioLog == null || ioSession < 0) return;
        try {
            stepCount++;
            String m = "TOOL: ask_worker\nINPUT:\n" + workerChatName + "\n" + prompt
                    + "\nOBSERVATION:\n" + (reply == null ? "no reply from worker" : reply);
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/step" + stepCount + "/tool", m);
            logToConversationSquad("INFO", "turn" + ioTurnNo + "/step" + stepCount + "/tool: ask_worker "
                    + workerChatName);
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log worker request record failed", e);
        }
    }

    /**
     * Records the judging call as this turn's next LLM step, in the same form the agent loop's own
     * calls use ({@code SupervisorTurnInTheRecord_260913_oo01}), so what was judged, against which
     * criteria, and what the verdict was are read the same way as any other step.
     */
    private void recordJudgeIo(String prompt, String verdict) {
        if (ioLog == null || ioSession < 0) return;
        stepCount++;
        recordStepIo(prompt, verdict, null, List.of());
    }

    /**
     * The {@code cancel_request} tool: takes a request this conversation sent out of the other conversation's
     * queue, and when that worked, lets go of it here: a request this turn sent for a request it received no
     * longer holds that request open, and the timekeeper no longer waits for its reply
     * ({@code CancelARequestInTheQueue_261113_oo01}).
     *
     * <p>A request this conversation still holds open but this run of the instance did not send (it was sent before a
     * restart, or to a conversation since deleted) cannot be taken out of any queue, and no reply to it is
     * awaited by anything that would deliver one; it is let go here, so the timekeeper stops reminding of it.</p>
     */
    private String cancelRequest(String requestId) {
        String result = AskChatTool.cancel(system, myChatName(), requestId);
        if (result.startsWith("removed") && openRequests.close(requestId.trim())) publishOutstanding();
        if (result.startsWith("not found") && openRequests.close(requestId.trim())) {
            publishOutstanding();
            return closedNotFoundText(requestId.trim());
        }
        return result;
    }

    /** What {@code cancel_request} says when it let go of an open request this run did not send. */
    static String closedNotFoundText(String requestId) {
        return "closed: request " + requestId + " was not sent in this run of the instance, so it is in no queue to "
                + "take out of. It was still open here; it is now closed, it no longer holds any request you received "
                + "open, and the timekeeper no longer reminds you of it. If a reply to it arrives anyway, it comes as "
                + "a message in your queue.";
    }

    /**
     * The conversation {@code targetName} was deleted: every request this conversation sent to it and still holds
     * open is let go, since no turn of the deleted conversation will answer it, and the timekeeper stops reminding
     * of it ({@code CancelARequestInTheQueue_261113_oo01}).
     *
     * @param targetName the deleted conversation's qualified name
     * @return the requests let go
     */
    public List<String> targetDeleted(String targetName) {
        List<String> closed = new java.util.ArrayList<>();
        for (OpenRequests.Outstanding o : openRequests.outstandingChildren()) {
            if (targetName.equals(o.target()) && openRequests.close(o.requestId())) closed.add(o.requestId());
        }
        if (!closed.isEmpty()) {
            publishOutstanding();
            logger.info(myChatName() + ": closed requests to the deleted " + targetName + ": " + closed);
        }
        return closed;
    }

    /** Between a conversation's name and its node, in what the timekeeper watches for a request sent to another instance. */
    public static final String REMOTE_TARGET_MARK = " on node ";

    /**
     * A request this conversation sent to another instance was taken out of the queue there: it no longer waits for
     * a reply ({@code ConversationSquadsAcrossInstances_261115_oo01}).
     *
     * @param requestId the request taken back
     */
    public void remoteRequestWithdrawn(String requestId) {
        if (openRequests.close(requestId)) publishOutstanding();
    }

    /**
     * The {@code accept_reply} tool: records that the reply this turn began with is enough
     * ({@code ReplyDecisionAndPromisedReport_261108_oo01}).
     *
     * @param requestId the request the reply answers; blank means the one this turn began with
     */
    private String acceptReply(String requestId) {
        // A request counted as out again after a promised report is let go: its original request no longer waits.
        if (!requestId.isBlank() && openRequests.close(requestId)) {
            publishOutstanding();
            if (undecidedRequest != null && requestId.equals(undecidedRequest)) {
                undecidedRequest = null;
                undecidedReplier = null;
            }
            return "ok: request " + requestId + " was still open; it is now closed";
        }
        // Each answer says what happened to the request, not what it no longer does
        // (TurnWithoutAnAnswerTellsTheAsker_261119_oo01).
        if (undecidedRequest != null && (requestId.isBlank() || requestId.equals(undecidedRequest))) {
            String accepted = undecidedRequest;
            undecidedRequest = null;
            undecidedReplier = null;
            return "ok: request " + accepted + " was already closed by its reply; your acceptance of that reply is recorded";
        }
        if (requestId.isBlank()) return "ok: no request was named and no reply in this turn waited for a decision; nothing changed";
        return "ok: request " + requestId + " was not open; nothing changed";
    }

    /**
     * The {@code expect_report} tool: registers, with the timekeeper of the conversation that promised a
     * report, that it is to be reminded after {@code minutes}. The answer of the turn the reminder starts
     * comes back to this conversation as a follow-up to the request. Registering this conversation itself,
     * with no request, is a promise made to the person: the reminder's answer stays on this conversation's
     * screen ({@code ReplyDecisionAndPromisedReport_261108_oo01}).
     */
    private String expectReport(String chatId, String requestId, String node, String minutesText) {
        long minutes;
        try {
            minutes = Long.parseLong(minutesText.trim());
        } catch (NumberFormatException e) {
            minutes = -1;
        }
        if (minutes <= 0) return "error: minutes must be a whole number above 0";
        if (!node.isBlank()) {
            // A conversation of another chat-ui instance (ConversationSquadsAcrossInstances_261115_oo01).
            if (chatId.isBlank()) return "error: give chatId, the conversation on node " + node.strip() + " that promised the report";
            String unqualified = AskChatTool.remoteTargetRefusal(chatId, node, "chatId");
            if (unqualified != null) return unqualified;
            if (requestId.isBlank()) return "error: give requestId, the request whose report was promised";
            String target = ChatUiActorSystem.resolveChatName(projectId, chatId);
            String result = AskChatTool.expectReportRemote(system, myChatName(), node, target, requestId, minutes);
            if (result.startsWith("error")) return result;
            if (undecidedRequest != null && requestId.equals(undecidedRequest)) {
                undecidedRequest = null;
                undecidedReplier = null;
            }
            return result + reopenAfterPromise(requestId, target + REMOTE_TARGET_MARK + node.strip(), minutes);
        }
        String target = chatId.isBlank() ? myChatName() : ChatUiActorSystem.resolveChatName(projectId, chatId);
        boolean self = target.equals(myChatName());
        if (!self && requestId.isBlank()) {
            return "error: give requestId, the request whose report " + target + " promised";
        }
        ActorRef<Timekeeper> keeper = system == null ? null : system.getActor(target + ".timekeeper");
        if (keeper == null) return "error: no conversation " + target + " with a timekeeper";
        String request = self ? null : requestId;
        String asker = self ? null : myChatName();
        long wait = minutes;
        int number;
        try {
            number = keeper.ask(k -> k.expectReport(request, asker, wait)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "error: could not reach " + target + "'s timekeeper: " + e.getMessage();
        }
        if (undecidedRequest != null && (requestId.isBlank() || requestId.equals(undecidedRequest))) {
            undecidedRequest = null;
            undecidedReplier = null;
        }
        if (self) {
            return "ok: your timekeeper will remind you in " + minutes + " minutes (reminder " + number
                    + ") of the report you promised the person";
        }
        return "ok: " + target + "'s timekeeper will remind it in " + minutes + " minutes (reminder " + number
                + ") to report on request " + requestId + "; its answer will come to you as a follow-up"
                + reopenAfterPromise(requestId, target, minutes);
    }

    /**
     * When the request {@code expect_report} names is one this conversation sent, counts it as not yet returned
     * again, so the request this conversation received for that work stays open until the report arrives
     * ({@code ReplyDecisionAndPromisedReport_261108_oo01}).
     *
     * @return the sentence to add to the tool's result, or {@code ""} when the request is not one this
     *         conversation sent
     */
    private String reopenAfterPromise(String requestId, String target, long minutes) {
        if (!requestId.startsWith(myChatName() + ":")) return "";
        long now = System.currentTimeMillis();
        OpenRequests.Reopening result = openRequests.reopen(requestId, target, now);
        // The request waits for the promised report: the "no reply yet" watch leaves it alone until the reminder's
        // follow-up is read (AutomaticReplyDecision_261119_oo01).
        if (result == OpenRequests.Reopening.REOPENED || result == OpenRequests.Reopening.STILL_OUT) {
            openRequests.promised(requestId, target, now + minutes * 60_000L, result == OpenRequests.Reopening.STILL_OUT);
            publishOutstanding();
        }
        switch (result) {
            case REOPENED:
                publishOutstanding();
                return "; the request you received for this work stays open until that report arrives, and your reply to "
                        + "it will then carry the report (accept_reply on the follow-up, or on request " + requestId
                        + " to stop waiting)";
            case ALREADY_ANSWERED:
                return "; the request you received for this work was already answered, so nothing is held open";
            default:
                return "";
        }
    }

    /**
     * Agent-loop action {@code think-cut-off}: the step that just ran was cut off at the output limit
     * ({@code finish_reason=length}). The provider has kept the partial reply in the history, so this
     * queues a note as the next step's prompt, telling the model how much it wrote and to continue in
     * smaller pieces, and the turn goes on under the step limit. After {@code maxInARow} cut-offs in a
     * row the turn ends instead, with what was written kept as its answer
     * ({@code CutOffReplyContinues_261115_oo01}). Fails when the last step was not cut off.
     *
     * @param maxInARow how many consecutive cut-offs the turn continues through; blank or unparsable
     *                  falls back to {@link #DEFAULT_MAX_CUT_OFFS_IN_A_ROW}
     * @return {@code success=true} when the note was queued as the next step's prompt
     */
    public ActionResult continueAfterCutOff(String maxInARow) {
        if (!cutOffPending) return new ActionResult(false, "not cut off");
        cutOffPending = false;
        if (cancelled || pauseRequested) return new ActionResult(false, "paused or cancelled");
        int max = parsePositiveOr(maxInARow, DEFAULT_MAX_CUT_OFFS_IN_A_ROW);
        if (cutOffRun > max) {
            String kept = cutOffText.toString().strip();
            finalAnswer = (kept.isEmpty() ? "" : kept + "\n\n")
                    + "(The turn ended: the reply was cut off at the output limit " + cutOffRun
                    + " times in a row. What was written is kept above.)";
            logToConversationSquad("WARNING", "turn" + ioTurnNo + ": ended after " + cutOffRun
                    + " cut-off replies in a row");
            return new ActionResult(false, "too many cut-offs in a row");
        }
        String written = cutOffChars == 0
                ? "no text before it was cut (the output limit was spent on thinking)"
                : cutOffChars + " characters";
        String note = "Your last reply was cut off at the output limit after " + written + ", so it was not "
                + "finished and no tool in it was run. Continue in smaller pieces. If you were writing a long "
                + "document, write it in several file writes, or append it part by part, each well under the "
                + "limit, rather than in one huge call. Do not repeat what you already wrote.";
        pendingPrompt = note;
        turnTranscript.append(note).append("\n\n");
        turnEmitter.accept(ChatEvent.thinking("\n→ " + note + "\n"));
        return new ActionResult(true, "continuing after a cut-off");
    }

    /**
     * Agent-loop action {@code think-repair}: the step that just ran tried to call a tool, but the call
     * cannot be run as written (a quote mark that is not ASCII, no parameter tags, a missing close tag, a
     * required argument left out). The reply stays in the provider's history; this queues a note saying
     * exactly what was wrong and showing the call in its exact form as the next step's prompt, and the
     * turn goes on under the step limit. After {@code maxInARow} broken calls in a row the turn ends
     * instead, with the reply kept as its answer and a line saying why
     * ({@code BrokenToolCallRepair_261115_oo01}). Fails when the last step was not a broken call.
     *
     * @param maxInARow how many consecutive broken calls the turn repairs; blank or unparsable falls back
     *                  to {@link #DEFAULT_MAX_REPAIRS_IN_A_ROW}
     * @return {@code success=true} when the note was queued as the next step's prompt
     */
    public ActionResult repairBrokenCall(String maxInARow) {
        if (!repairPending) return new ActionResult(false, "no broken tool call");
        repairPending = false;
        if (cancelled || pauseRequested) return new ActionResult(false, "paused or cancelled");
        int max = parsePositiveOr(maxInARow, DEFAULT_MAX_REPAIRS_IN_A_ROW);
        if (repairRun > max) {
            String kept = repairText == null ? "" : repairText.strip();
            finalAnswer = (kept.isEmpty() ? "" : kept + "\n\n")
                    + "(The turn ended: a tool call could not be run as written " + repairRun
                    + " times in a row. The last reply is kept above.)";
            logToConversationSquad("WARNING", "turn" + ioTurnNo + ": ended after " + repairRun
                    + " broken tool calls in a row");
            return new ActionResult(false, "too many broken tool calls in a row");
        }
        String note = brokenCallNote(repairProblems, repairTool);
        finalAnswer = null;
        pendingPrompt = note;
        turnTranscript.append(note).append("\n\n");
        turnEmitter.accept(ChatEvent.thinking("\n→ " + note + "\n"));
        return new ActionResult(true, "repairing a broken tool call");
    }

    /**
     * The note sent after a broken tool call: what was wrong with it, and the call written out in the
     * exact form, with each argument the tool needs.
     */
    static String brokenCallNote(List<String> problems, String tool) {
        StringBuilder note = new StringBuilder("Your last reply tried to call a tool, but the call could not be run "
                + "as written, so no tool ran:\n");
        for (String p : problems) note.append("- ").append(p).append("\n");
        note.append("Write the call again, exactly in this form: ASCII double quotes, each argument in its own "
                + "parameter tag, and the call closed with </invoke>.\n");
        note.append(exampleCall(tool));
        return note.toString();
    }

    /** One call to {@code tool} in the exact form, with a placeholder for each argument it needs. */
    static String exampleCall(String tool) {
        String name = (tool == null || tool.isBlank()) ? "read" : tool;
        StringBuilder call = new StringBuilder("<invoke name=\"").append(name).append("\">\n");
        List<String> args = requiredParameters(name);
        if ("read".equals(name)) {
            call.append("<parameter name=\"path\">first/file.md\nsecond/file.md</parameter>\n");
        } else if (args.isEmpty() && !TOOL_DESCRIPTIONS.containsKey(name)) {
            call.append("<parameter name=\"NAME\">VALUE</parameter>\n");
        } else {
            for (String a : args) call.append("<parameter name=\"").append(a).append("\">...</parameter>\n");
        }
        call.append("<reason>one concise sentence on why you need this now</reason>\n</invoke>");
        return call.toString();
    }

    /**
     * Agent-loop action {@code think-undecided}: when this turn began with a reply, the model wrote its
     * answer without recording a decision on it, and has not been asked yet, keeps the answer and sends the
     * model a note asking for one, in the same turn. Fails otherwise, so the loop goes on to
     * {@code think-final} ({@code ReplyDecisionAndPromisedReport_261108_oo01}).
     *
     * @return {@code success=true} when the note was queued as the next step's prompt
     */
    public ActionResult replyLeftUndecided() {
        // A turn being paused or cancelled is not ending: the decision, if still owed, is asked for when it resumes.
        if (cancelled || pauseRequested) return new ActionResult(false, "paused or cancelled");
        // Asking the person is how this turn ends; the decision on the reply follows the person's answer.
        if (personQuestion != null) return new ActionResult(false, "asked the person");
        if (undecidedRequest == null) return new ActionResult(false, "no reply waiting for a decision");
        if (decisionAsked) {
            logToConversationSquad("WARNING", "turn" + ioTurnNo + ": no decision was recorded on the reply to request "
                    + undecidedRequest + " from " + undecidedReplier);
            return new ActionResult(false, "asked once already");
        }
        decisionAsked = true;
        answerBeforeDecision = finalAnswer;
        String note = "This turn began with " + undecidedReplier + "'s reply to request " + undecidedRequest
                + ", and you have recorded no decision on it. Call accept_reply if it is done, expect_report if a "
                + "report is still to come, or ask_chat to " + undecidedReplier + " to ask for a fix or a question.";
        pendingPrompt = note;
        turnTranscript.append(note).append("\n\n");
        turnEmitter.accept(ChatEvent.thinking("\n→ " + note + "\n"));
        return new ActionResult(true, "asked for a decision");
    }

    /** What {@link #undecidedEnding} holds for a message saying the request ended without a reply. */
    static final String ENDED_WITHOUT_A_REPLY = "without a reply";

    private static final java.util.regex.Pattern ENDED_LINE = java.util.regex.Pattern.compile("(?m)^\\[ended: ([a-z-]+);");

    /**
     * Reads how a reply message ended from the line the sending side put on it, never from what the reply says.
     *
     * @param message the reply, follow-up or no-reply message a turn began with
     * @return the ended line's word, {@link #ENDED_WITHOUT_A_REPLY}, or {@code null} for a follow-up or a message
     *         with no ended line
     */
    static String replyEndingOf(String message) {
        if (message == null || message.startsWith("[follow-up to request ")) return null;
        String first = message.lines().findFirst().orElse("");
        if (first.startsWith("[request ") && first.contains(" ended without a reply")) return ENDED_WITHOUT_A_REPLY;
        java.util.regex.Matcher m = ENDED_LINE.matcher(message);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    /**
     * Agent-loop action at the end of a turn: when the turn began with a reply or a follow-up and recorded no decision
     * on it (accept_reply, expect_report, or ask_chat to the one that replied), records one, from facts only: the
     * request id, the reply's ended line and how this turn ended. It accepts the reply, unless the reply ended
     * {@code asked} or {@code step-limit}, or this turn itself did not end normally; then it records no decision and
     * warns. Each is written to the I/O log as {@code turn{N}/reply-decision} and to the conversation's log. Settles
     * once per turn and always succeeds, so it never keeps the turn from ending
     * ({@code AutomaticReplyDecision_261119_oo01}).
     */
    public ActionResult decideReplyAtTurnEnd() {
        if (replyDecisionSettled) return new ActionResult(true, "already settled");
        replyDecisionSettled = true;
        if (undecidedRequest == null) return new ActionResult(true, "no reply waiting for a decision");
        // A paused turn is not ending: the turn that resumes it decides. Nor is one a recovery stopped: its prompt runs
        // again, and that turn decides.
        if (pauseRequested) return new ActionResult(true, "paused: the resumed turn decides");
        if (recoverRequested) return new ActionResult(true, "stopped by stalled-turn recovery: the turn run again decides");
        if (usageLimitMessage != null) return new ActionResult(true, "refused for the usage limit: the turn run again decides");
        String request = undecidedRequest;
        String replier = undecidedReplier;
        String leftOpen = personQuestion != null ? "this turn ended by asking the person"
                : stepLimitHit ? "this turn reached its step limit"
                : cancelled ? "this turn was cancelled"
                : finalAnswer == null && turnError != null ? "this turn failed: " + turnError
                : com.scivicslab.chatui.agent.ChatInspection.ENDED_ASKED.equals(undecidedEnding)
                        || com.scivicslab.chatui.agent.ChatInspection.ENDED_STEP_LIMIT.equals(undecidedEnding)
                        ? "the reply ended " + undecidedEnding
                : null;
        if (leftOpen != null) {
            String line = "LEFT OPEN | " + request + " | " + leftOpen + "; no decision is recorded on " + replier + "'s reply";
            recordReplyDecision(line);
            logToConversationSquad("WARNING", "turn" + ioTurnNo + ": " + line);
            return new ActionResult(true, "left open");
        }
        String why = ENDED_WITHOUT_A_REPLY.equals(undecidedEnding) ? "the request ended without a reply"
                : undecidedEnding == null ? "the follow-up's turn registered no new expect_report"
                : "the reply ended " + undecidedEnding + " and the turn recorded no decision";
        undecidedRequest = null;
        undecidedReplier = null;
        String line = "AUTO ACCEPT | " + request + " | " + why;
        recordReplyDecision(line);
        logToConversationSquad("INFO", "turn" + ioTurnNo + ": accept_reply recorded automatically: " + line);
        return new ActionResult(true, "accepted");
    }

    private void recordReplyDecision(String line) {
        if (ioLog == null || ioSession < 0) return;
        try {
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/reply-decision", line);
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log reply-decision record failed", e);
        }
    }

    /**
     * @param source a queued message's source
     * @return whether it resumes a paused turn: {@link #RESUME_SOURCE}, alone or followed by the paused turn's id
     */
    public static boolean isResume(String source) {
        return source != null && (source.equals(RESUME_SOURCE) || source.startsWith(RESUME_SOURCE + ":"));
    }

    /** @return the paused turn's id a resume message's source names, or {@code ""} when it names none */
    private static String pausedTurnIdOf(String source) {
        return source.length() > RESUME_SOURCE.length() ? source.substring(RESUME_SOURCE.length() + 1) : "";
    }

    /** @return who waits for a reminder's answer, read from the reminder's source, or {@code null} */
    private static FollowUpTo reminderOf(String source) {
        if (source == null || !source.startsWith(REMINDER_SOURCE_PREFIX)) return null;
        String rest = source.substring(REMINDER_SOURCE_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon < 0) return null;
        try {
            return new FollowUpTo(rest.substring(colon + 1), Integer.parseInt(rest.substring(0, colon)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @return whether {@code tool} is a built-in tool that drives the conversation, which this session runs itself */
    private static boolean drivesConversation(String tool) {
        ToolCatalog.Spec spec = ToolCatalog.builtIn(tool);
        return spec != null && spec.drivesConversation();
    }

    /**
     * Why a call the server read from a reply cannot be run, in the words its result gives the model
     * ({@code OpenAiCompatStandardToolCalling_261120_oo01}); {@code null} when it can be run.
     */
    private String whyNotRunnable(ToolCall tc) {
        String prefix = "error: the call could not be run: ";
        if (!toolOfThisConversation(tc.name()) && !isExternalTool(tc.name())) {
            return prefix + "there is no tool named \"" + tc.name() + "\". Call one of the tools you were given.";
        }
        JSONObject args;
        try {
            args = new JSONObject(tc.argumentsJson() == null || tc.argumentsJson().isBlank() ? "{}" : tc.argumentsJson());
        } catch (Exception e) {
            return prefix + "the arguments are not a JSON object. Call " + tc.name()
                    + " again with its arguments as one JSON object.";
        }
        List<String> missing = requiredParameters(tc.name()).stream()
                .filter(a -> !args.has(a) || args.isNull(a) || args.optString(a, "").isBlank()).toList();
        if (missing.isEmpty()) return null;
        String what = missing.size() == 1 ? "the parameter \"" + missing.get(0) + "\" is missing"
                : "the parameters " + String.join(", ", missing.stream().map(a -> "\"" + a + "\"").toList()) + " are missing";
        return prefix + what + ". Call " + tc.name() + " again with every required parameter.";
    }

    /** Dispatches one tool call to its implementation and returns the raw (untruncated) observation. */
    private String executeTool(ToolCall tc) {
        return executeTool(tc, false);
    }

    private String executeTool(ToolCall tc, boolean summarizingPages) {
        String args = tc.argumentsJson();
        if (!toolSet.contains(tc.name())) {
            return "error: tool '" + tc.name() + "' is not available in this conversation";
        }
        turnToolCalls++;
        return switch (tc.name()) {
            case "ask_chat", "ask_chat_urgent" -> {
                String requestId = nextRequestId();
                String node = extractInput(args, "node").strip();
                boolean urgentAsk = "ask_chat_urgent".equals(tc.name());
                // A conversation of another chat-ui instance (ConversationSquadsAcrossInstances_261115_oo01); the
                // timekeeper names it with its node, so its watch reads that instance, not one of this name here.
                String sent = !node.isEmpty()
                        ? AskChatTool.sendRemote(system, projectId, chatId, node,
                                extractInput(args, "chatId"), extractInput(args, "prompt"), requestId, urgentAsk)
                        : urgentAsk
                        ? AskChatTool.sendUrgent(system, projectId, chatId,
                                extractInput(args, "chatId"), extractInput(args, "prompt"), requestId)
                        : AskChatTool.send(system, projectId, chatId,
                                extractInput(args, "chatId"), extractInput(args, "prompt"), requestId);
                String watched = ChatUiActorSystem.resolveChatName(projectId, extractInput(args, "chatId"))
                        + (node.isEmpty() ? "" : REMOTE_TARGET_MARK + node);
                // A request back to the conversation whose reply this turn decides on is a decision.
                if (sent.startsWith("sent to ") && node.isEmpty() && undecidedReplier != null && undecidedReplier.equals(
                        ChatUiActorSystem.resolveChatName(projectId, extractInput(args, "chatId")))) {
                    undecidedRequest = null;
                    undecidedReplier = null;
                }
                // Sent for the request this turn works for: that request waits for its reply.
                if (turnOriginal != null && sent.startsWith("sent to ")) {
                    openRequests.childSent(turnOriginal, requestId, watched, System.currentTimeMillis());
                    publishOutstanding();
                } else if (sent.startsWith("sent to ")) {
                    // A turn the person's prompt started: no request waits for this one, but the timekeeper's
                    // child-reply watch covers it all the same (ReminderDecidedAtTheEndOfTheTurn_261114_oo01).
                    openRequests.sentWithoutAnOriginal(requestId, watched, System.currentTimeMillis());
                    publishOutstanding();
                }
                yield sent;
            }
            case "accept_reply" -> acceptReply(extractInput(args, "requestId"));
            case "cancel_request" -> cancelRequest(extractInput(args, "requestId"));
            case "expect_report" -> expectReport(extractInput(args, "chatId"), extractInput(args, "requestId"), extractInput(args, "node"),
                    extractInput(args, "minutes"));
            // Every tool that does not drive the conversation, the plugin tools among them.
            default -> toolRunner().run(tc.name(), args, summarizingPages);
        };
    }

    /** Extracts one named field from a tool call's JSON arguments, or {@code ""} if absent. */
    private String extractInput(String argumentsJson, String field) {
        try {
            return new org.json.JSONObject(argumentsJson == null ? "{}" : argumentsJson).optString(field, "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Summarises web pages through the same server and model the conversation uses, one
     * completion per page, outside the conversation's own history.
     */
    private com.scivicslab.chatui.agent.WebPageSummarizer pageSummarizer() {
        var openAi = (com.scivicslab.chatui.openaicompat.OpenAiCompatProvider) provider;
        return new com.scivicslab.chatui.agent.WebPageSummarizer(
                page -> openAi.completeOutsideConversation(
                        com.scivicslab.chatui.agent.WebPageSummarizer.promptFor(page)));
    }

    // ---- Autonomous turns (idle monitor) ----

    /**
     * Idle-monitor entry point. When the session is idle and the provider has buffered autonomous
     * output — output produced outside a {@code sendPrompt} turn, e.g. a background job the model
     * started finishing — drains it as its own assistant turn: streamed to the browser over SSE and
     * recorded in history, with no preceding user message.
     *
     * <p>Invoked periodically via {@code self.tell} by the scheduler in {@code ChatUiActorSystem}.
     * Following the POJO-actor model, the cheap {@code hasAutonomousActivity()} check runs here on
     * the actor thread, while the blocking drain is delegated to the managed thread pool so the
     * actor's message loop stays responsive.</p>
     *
     * @param self this actor's own reference, used to queue completion back onto the actor thread
     */
    public void pollAutonomousActivity(ActorRef<ChatSession> self) {
        if (busy || providerName == null) return;
        if (!provider.supportsAutonomousEvents() || !provider.hasAutonomousActivity()) return;

        // Reserve the session so a user prompt cannot start while the autonomous turn streams.
        busy = true;
        emitToSse(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), true));
        final long ioSession = (ioLog != null) ? ioLog.ensureSession(myChatName()) : -1;
        ActorRef<LlmProvider> providerRef = providerRef();

        providerRef.ask(p -> {
            StringBuilder assistantBuf = new StringBuilder();
            StringBuilder thinkingBuf = new StringBuilder();
            Consumer<ChatEvent> wrapped = event -> {
                if ("delta".equals(event.type()) && event.content() != null) {
                    assistantBuf.append(event.content());
                } else if ("thinking".equals(event.type()) && event.content() != null) {
                    thinkingBuf.append(event.content());
                } else if ("result".equals(event.type())) {
                    String content = assistantBuf.toString();
                    String thinking = thinkingBuf.toString();
                    if (!content.isBlank()) {
                        self.tell(b -> b.recordAutonomousTurn(ioSession, content, thinking));
                    }
                }
                emitToSse(event);
            };
            return p.drainAutonomousActivity(wrapped);
        }, system.getManagedThreadPool())
        .whenComplete((happened, ex) -> self.tell(b -> b.onAutonomousComplete(self)));
    }

    /**
     * Called when an autonomous drain finishes; queued back onto the actor via {@code self.tell}.
     *
     * @param self this actor's own reference, forwarded to {@code PromptQueue} to dispatch any queued prompt
     */
    public void onAutonomousComplete(ActorRef<ChatSession> self) {
        busy = false;
        // A user prompt may have queued while we held the session — let the queue dispatch it now.
        ActorRef<PromptQueue> promptQueue = getPromptQueue();
        if (promptQueue != null) promptQueue.tell(q -> q.onPromptComplete(self));
        emitToSse(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), false));
    }

    /**
     * Records a completed autonomous turn (one with no user prompt) into conversation history and
     * the I/O log. Runs on the actor thread so {@code ioTurn} is mutated safely.
     *
     * @param ioSession the open I/O-log session id, or negative when logging is disabled
     * @param assistant the accumulated assistant text
     * @param thinking  the accumulated reasoning text
     */
    public void recordAutonomousTurn(long ioSession, String assistant, String thinking) {
        // A turn of its own, numbered like any other, so its line in the record carries its own id.
        this.ioSession = ioSession;
        this.ioTurnNo = ++ioTurn;
        this.askCount = 0;
        recordHistory("assistant", assistant);
        recordTurnIo(ioSession, ioTurnNo, "(autonomous continuation)", assistant, thinking);
        // Which request this turn works for is known only inside the provider, so its answer stays on the
        // screen and in the record. A report reaches the asker through a reminder, which names the request
        // (ReplyDecisionAndPromisedReport_261108_oo01).
    }

    /**
     * Cancels the currently running prompt, if any — a plain {@code startPrompt} call or an
     * agent-loop turn alike.
     *
     * <p>Uses {@code tellNow()} to bypass the provider actor's queue so that the
     * cancel signal reaches the provider immediately, even while {@code sendPrompt()}
     * is blocking the queue. {@code cancelled} is checked by {@link #stepExpectingAction()}
     * at the top of each step so an agent-loop turn stops advancing once the in-flight
     * provider call this unblocks returns.</p>
     */
    public void cancel() {
        cancelled = true;
        if (providerName == null) return;
        ActorRef<LlmProvider> providerRef = providerRef();
        if (providerRef != null) providerRef.tellNow(LlmProvider::cancel);
    }

    /**
     * When the model call in flight began, if it has produced nothing at all since; readable from any thread, while a
     * turn holds this actor's own.
     *
     * @return the call's start in milliseconds since the epoch, or 0 when no call is in flight or it has produced output
     */
    public long silentCallStartedAt() {
        long started = llmCallStartedAt;
        return started == 0 || llmCallProducedOutput ? 0 : started;
    }

    /** @return when the queue was held for a usage-limit refusal, or 0 when it is not; readable from any thread */
    public long limitHeldSince() { return limitHeldSince; }

    /** Forgets the usage-limit hold; the recovery releases the queue itself. */
    public void clearLimitHold() { limitHeldSince = 0; }

    /** @return whether a stalled-turn recovery has stopped the running turn and it has not ended yet */
    public boolean isRecovering() { return recoverRequested; }

    /**
     * Stops the running turn so that the prompt it works on goes back to the front of the queue, unanswered, and is
     * run again ({@code StalledTurnRecovery}). Sent with {@code tellNow}, because the turn holds this actor's thread.
     * The turn ends as a cancelled one does, except that its request is not answered, no reply decision is recorded,
     * and the queue is told to put the prompt back instead of starting the next one. Does nothing when no turn runs.
     *
     * @return whether a turn was running and was asked to stop
     */
    public boolean stopForRecovery() {
        if (!busy) return false;
        recoverRequested = true;
        logToConversationSquad("WARNING", "stalled-turn recovery: stopping " + turnId()
                + "; its prompt goes back to the front of the queue");
        cancel();
        return true;
    }

    /** Kills the provider's process for a stalled-turn recovery whose cancel did not end the turn ({@link LlmProvider#forceStop}). */
    public void forceStopProvider() {
        if (providerName == null) return;
        ActorRef<LlmProvider> providerRef = providerRef();
        if (providerRef != null) providerRef.tellNow(LlmProvider::forceStop);
    }

    /**
     * After {@link #stopForRecovery}, when the turn did not end: the step stops waiting for the model call, which has
     * been cancelled and whose process has been killed, and the turn ends. Sent with {@code tellNow}.
     */
    public void abandonCallForRecovery() {
        if (!recoverRequested) return;
        abandonCall = true;
        logToConversationSquad("WARNING", "stalled-turn recovery: the model call of " + turnId()
                + " did not end when cancelled; the turn stops waiting for it");
    }

    /**
     * Sends a user response to an interactive prompt identified by {@code promptId}.
     *
     * @param promptId the identifier of the prompt awaiting a response
     * @param response the user's response text
     * @throws IOException if communicating with the provider fails
     */
    public void respond(String promptId, String response) throws IOException {
        provider.respond(promptId, response);
    }

    // ---- History ----

    /**
     * Appends an entry to the conversation history, evicting the oldest entry
     * when the maximum history size is exceeded.
     *
     * <p>Blank or null content is silently ignored.</p>
     *
     * @param role    the message role (e.g. "user" or "assistant")
     * @param content the message text
     */
    public void recordHistory(String role, String content) {
        recordHistory(role, content, null);
    }

    /**
     * Stops the running turn so that an urgent request waiting at the front of the queue is answered
     * next, and has the work go on after it ({@code TimekeeperPausesATurnForAnUrgentRequest_261107_oo01}).
     * Called by this conversation's timekeeper with {@code tellNow}, because a running turn holds
     * this actor's thread. Does nothing when no turn is running.
     */
    public void pauseForUrgent() {
        if (!busy || pauseRequested) return;
        pauseRequested = true;
        logToConversationSquad("INFO", "paused " + turnId() + " for an urgent request");
        cancel();
    }

    /**
     * Puts the message that resumes the paused turn into this conversation's queue, right after the
     * urgent items, carrying the paused turn's original request.
     */
    private void queueResume(Consumer<ChatEvent> emitter, ActorRef<ChatSession> self) {
        ActorRef<PromptQueue> queue = getPromptQueue();
        if (queue == null) return;
        String original = turnOriginal;
        // The work is repeated here: the history also holds the urgent request and every other message, and a
        // model told only to "continue" may continue the wrong one. Everything else the turn knew about its
        // purpose is kept under its own id, which the resume message's source names.
        String pausedId = turnId();
        pausedTurns.put(pausedId, new PausedTurn(turnTask, undecidedRequest, undecidedReplier, undecidedEnding, turnReminder, turnFollowUp));
        String text = "[resume paused turn " + turnId() + (original == null ? "" : ", for request " + original)
                + "]\nYour previous turn was paused so that an urgent request could be answered. Continue the work "
                + "it was doing for the message below, and answer that message only; what it had done so far is in "
                + "your previous answer.\n\n" + turnTask;
        queue.tell(q -> q.enqueue(text, null, "queue", emitter, self, RESUME_SOURCE + ":" + pausedId, null,
                new CompletableFuture<>(), false, true, List.of(), original, true));
    }

    /** Publishes the child requests still out for the timekeeper to read. */
    private void publishOutstanding() {
        outstandingSnapshot = List.copyOf(openRequests.outstandingChildren());
        promisedSnapshot = openRequests.promisedRequests();
        recordOpenRequests();
    }

    /** @return the child requests still out; readable from any thread */
    public List<OpenRequests.Outstanding> outstandingChildren() {
        return outstandingSnapshot;
    }

    /**
     * Returns the current turn's id, {@code <conversation>:s<session>:t<turn>}
     * ({@code TurnAndRequestIds_261107_oo01}); without an I/O log, {@code <conversation>:t<turn>}.
     */
    public String turnId() {
        return turnIdOf(myChatName(), ioSession, ioTurnNo);
    }

    /** @return a turn id made of its three parts, the session left out when there is none */
    static String turnIdOf(String conversation, long session, int turn) {
        return conversation + (session >= 0 ? ":s" + session : "") + ":t" + turn;
    }

    /** @return the id of the next {@code ask_chat} request this turn sends, {@code <turn id>:r<n>} */
    public String nextRequestId() {
        return turnId() + ":r" + (++askCount);
    }

    /**
     * @param origin where a user line's prompt came from, or {@code null} for every other line
     */
    public void recordHistory(String role, String content, String origin) {
        recordHistory(role, content, origin, null, null);
    }

    /**
     * Appends a line brought back from the I/O log when the process starts. A line the log has no
     * time, model or duration for gets none, and the pane shows none: the time of the restart
     * would state something false.
     */
    public void restoreHistory(String role, String content) {
        restoreHistory(role, content, null, null, null);
    }

    /**
     * As {@link #restoreHistory(String, String)}, for a line whose time, model or duration the log
     * does record. Each is {@code null} when it does not.
     */
    public void restoreHistory(String role, String content, Long at, String model, Long durationMs) {
        if (content == null || content.isBlank()) return;
        conversationHistory.addLast(new HistoryEntry(role, content, null, at, model, durationMs));
        while (conversationHistory.size() > MAX_HISTORY) conversationHistory.removeFirst();
        historySnapshot.set(List.copyOf(conversationHistory));
    }

    /**
     * @param model      the model that wrote an assistant line, or {@code null}
     * @param durationMs how long the turn took, for an assistant line, or {@code null}
     */
    public void recordHistory(String role, String content, String origin, String model, Long durationMs) {
        if (content == null || content.isBlank()) return;
        conversationHistory.addLast(new HistoryEntry(role, content, origin, System.currentTimeMillis(), model, durationMs,
                busy ? turnId() : null, busy ? turnRequestId : null));
        while (conversationHistory.size() > MAX_HISTORY) conversationHistory.removeFirst();
        historySnapshot.set(List.copyOf(conversationHistory));
    }

    /**
     * Records one completed Claude turn into the H2 I/O log in the marker format the Sessions tab reads
     * ({@code REQUEST:} = the user prompt as a one-message request, {@code RESPONSE:} = the assistant
     * text, {@code REASONING:} = thinking, {@code USAGE:} = token line). No-op when logging is off.
     */
    private void recordTurnIo(long ioSession, int turnNo, String prompt, String assistant, String thinking) {
        if (ioLog == null || ioSession < 0) return;
        try {
            String requestJson = new org.json.JSONObject()
                    .put("messages", new org.json.JSONArray().put(
                            new org.json.JSONObject().put("role", "user").put("content", prompt)))
                    .toString();
            StringBuilder m = new StringBuilder();
            m.append("REQUEST:\n").append(requestJson);
            m.append("\n\nRESPONSE:\n").append(assistant == null ? "" : assistant);
            if (thinking != null && !thinking.isBlank()) {
                m.append("\n\nREASONING:\n").append(thinking);
            }
            m.append("\n\nUSAGE: promptTokens=0 completionTokens=0");
            ioLog.record(ioSession, "agent", "turn" + turnNo + "/step1/llm", m.toString());
            logToConversationSquad("INFO", "turn" + turnNo + "/step1/llm");
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log turn record failed", e);
        }
    }

    /**
     * Writes a command that the backing process carried out (such as {@code /compact}) to the I/O
     * log: the line it was given and the text it answered. It is not a turn of the conversation,
     * so it has no turn number; it is kept under {@code command/<time>}, the way a one-shot call
     * is kept under {@code oneshot/<time>}.
     */
    private void recordCommandIo(String line, List<ChatEvent> answer) {
        if (ioLog == null) return;
        try {
            long session = ioLog.ensureSession(myChatName());
            ioLog.record(session, "agent", "command/" + System.currentTimeMillis(), commandIoRecord(line, answer));
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log command record failed", e);
        }
    }

    /** The I/O log content for one command: {@code REQUEST:} the line, {@code RESPONSE:} the answer, {@code ERROR:} a failure. */
    static String commandIoRecord(String line, List<ChatEvent> answer) {
        String requestJson = new org.json.JSONObject()
                .put("messages", new org.json.JSONArray().put(
                        new org.json.JSONObject().put("role", "user").put("content", line)))
                .toString();
        StringBuilder text = new StringBuilder();
        StringBuilder error = new StringBuilder();
        for (ChatEvent event : answer) {
            if (event.content() == null) continue;
            StringBuilder into = "error".equals(event.type()) ? error : "info".equals(event.type()) ? text : null;
            if (into == null) continue;
            if (!into.isEmpty()) into.append('\n');
            into.append(event.content());
        }
        StringBuilder m = new StringBuilder("REQUEST:\n").append(requestJson)
                .append("\n\nRESPONSE:\n").append(text);
        if (!error.isEmpty()) m.append("\n\nERROR:\n").append(error);
        return m.toString();
    }

    /**
     * Returns the most recent conversation history entries, up to the given limit.
     *
     * @param limit the maximum number of entries to return
     * @return an unmodifiable list of the most recent history entries
     */
    public List<HistoryEntry> getHistory(int limit) {
        int size = conversationHistory.size();
        int from = Math.max(0, size - limit);
        return Collections.unmodifiableList(new ArrayList<>(conversationHistory.subList(from, size)));
    }

    /**
     * Sets whether this conversation asks its model to skip the thinking phase
     * ({@code ThinkingAndEffortAreConversationSettings_260917_oo01}).
     *
     * <p>A workflow cannot pass the flag with each prompt -- {@code ask_chat} carries a prompt and
     * nothing else -- so a run that wants a model to answer without reasoning says so here, once,
     * the way it says which model to run on.</p>
     */
    public void setNoThink(boolean value) {
        this.noThinkAlways = value;
    }

    /** @return whether this conversation asks its model to skip the thinking phase */
    public boolean isNoThink() {
        return noThinkAlways;
    }

    // ── What this conversation records of itself (EachActorRecordsItsOwnState_261118_oo01) ──────────────

    /** Prefix of an agent-loop entry that names a workflow file under {@code /workflows/}. */
    public static final String AGENT_LOOP_FILE = "file:";
    /** Prefix of an agent-loop entry that holds the YAML itself, as {@code set_agent_loop} gave it. */
    public static final String AGENT_LOOP_YAML = "yaml:\n";

    /** This conversation's own session of the I/O log; {@code null} records nothing. */
    private volatile com.scivicslab.chatui.core.iolog.SessionEntries entries;

    /** Records this conversation's settings and agent loop, from now on, to its own session. */
    public void recordsTo(com.scivicslab.chatui.core.iolog.SessionEntries entries) {
        this.entries = entries;
    }

    /**
     * Writes the conversation's settings as one {@code settings} entry: the provider kind, the tool set, the model,
     * the temperature, whether it answers without thinking, and the effort, all of them every time, so the last
     * entry is the whole state ({@code ConversationSettingsRecord_260913_oo01}). The provider holds some of them and
     * is replaced whenever the provider changes, so this conversation, which holds the provider, reads them from it
     * and writes them. Safe from another thread: it only reads.
     *
     * @param model the model just set, when the provider may not be on it yet; {@code null} reads the provider's
     */
    public void recordSettings(String model) {
        com.scivicslab.chatui.core.iolog.SessionEntries e = entries;
        if (e == null) return;
        org.json.JSONObject o = new org.json.JSONObject();
        String kind = getProviderId();
        String m = model != null ? model : getModel();
        Double temperature = provider.getTemperature();
        String effort = getEffort();
        o.put("provider", kind == null ? org.json.JSONObject.NULL : kind);
        o.put("tools", toolSet == null ? org.json.JSONObject.NULL : toolSet.id());
        o.put("model", m == null ? org.json.JSONObject.NULL : m);
        o.put("temperature", temperature == null ? org.json.JSONObject.NULL : temperature);
        o.put("noThink", noThinkAlways);
        o.put("effort", effort == null ? org.json.JSONObject.NULL : effort);
        o.put("mcpServers", new org.json.JSONObject(mcpServers));
        e.write(com.scivicslab.chatui.core.iolog.IoLogView.SETTINGS_LABEL, o.toString());
    }

    /** Writes the settings once, for a conversation that has none recorded yet: one just made. */
    public void recordIfNew() {
        com.scivicslab.chatui.core.iolog.SessionEntries e = entries;
        if (e != null && e.latest(com.scivicslab.chatui.core.iolog.IoLogView.SETTINGS_LABEL) == null) recordSettings(null);
    }

    /**
     * Writes the agent loop put on this conversation; the last one is the loop a restart puts back.
     *
     * @param entry {@link #AGENT_LOOP_FILE} and a file name, or {@link #AGENT_LOOP_YAML} and the YAML
     */
    public void recordAgentLoop(String entry) {
        com.scivicslab.chatui.core.iolog.SessionEntries e = entries;
        if (e != null) e.write(com.scivicslab.chatui.core.iolog.IoLogView.AGENT_LOOP_LABEL, entry);
    }

    /** @return the settings this conversation last recorded, or {@code null} when it recorded none */
    public com.scivicslab.chatui.core.iolog.IoLogView.Settings recordedSettings() {
        com.scivicslab.chatui.core.iolog.SessionEntries e = entries;
        return e == null ? null : com.scivicslab.chatui.core.iolog.IoLogView.parseSettings(e.latest(com.scivicslab.chatui.core.iolog.IoLogView.SETTINGS_LABEL));
    }

    /** Label of the conversation's entry that holds its open requests. */
    public static final String OPEN_REQUESTS_LABEL = "open-requests";

    /**
     * Writes the requests this conversation received and has not answered, and those it sent and is waiting on, as
     * one {@code open-requests} entry ({@code EachActorRecordsItsOwnState_261118_oo01}). All of them every time.
     */
    /** The received requests not yet answered and who asked each, readable from any thread (a cancel from another instance). */
    private volatile java.util.Map<String, String> unansweredSnapshot = java.util.Map.of();

    /**
     * @return whether a request this conversation received from {@code asker} is still being worked on: started and not
     *         yet answered. Readable from any thread, without waiting for a running turn.
     */
    public boolean isWorkingOn(String requestId, String asker) {
        return asker != null && asker.equals(unansweredSnapshot.get(requestId));
    }

    private void recordOpenRequests() {
        unansweredSnapshot = openRequests.unanswered();
        com.scivicslab.chatui.core.iolog.SessionEntries e = entries;
        if (e != null) e.write(OPEN_REQUESTS_LABEL, openRequests.toJson().toString());
    }

    /**
     * Puts back the open requests last recorded, after a restart, and writes nothing. A received request not yet
     * answered is answered, when its work is done, to its asker by name ({@code AskChatTool.deliverReplyTo}); the
     * requests still out are watched again by the timekeeper. A turn that was running at the restart is not run again:
     * its request stays received, and the asker's own reply watch tells the asker.
     */
    public void restoreOpenRequests() {
        com.scivicslab.chatui.core.iolog.SessionEntries e = entries;
        String json = e == null ? null : e.latest(OPEN_REQUESTS_LABEL);
        if (json == null || json.isBlank()) return;
        try {
            openRequests.load(new org.json.JSONObject(json), (id, asker, urgent, link) -> {
                CompletableFuture<TurnReply> done = new CompletableFuture<>();
                done.whenCompleteAsync((reply, failure) -> {
                    if (reply == TurnReply.WITHDRAWN || asker == null) return;
                    if (link != null) {
                        com.scivicslab.chatui.agent.AskChatTool.deliverRemoteReply(system, link, asker, myChatName(), urgent, reply, failure);
                    } else {
                        com.scivicslab.chatui.agent.AskChatTool.deliverReplyTo(system, asker, myChatName(), id, urgent, reply, failure);
                    }
                });
                return done;
            });
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Could not restore the open requests of " + myChatName(), ex);
            return;
        }
        outstandingSnapshot = List.copyOf(openRequests.outstandingChildren());
        promisedSnapshot = openRequests.promisedRequests();
        unansweredSnapshot = openRequests.unanswered();
    }

    /** @return the agent loop this conversation last recorded, or {@code null} when it recorded none */
    public String recordedAgentLoop() {
        com.scivicslab.chatui.core.iolog.SessionEntries e = entries;
        String loop = e == null ? null : e.latest(com.scivicslab.chatui.core.iolog.IoLogView.AGENT_LOOP_LABEL);
        return loop == null || loop.isBlank() ? null : loop;
    }

    /**
     * Tells the provider how hard to work on one answer, where it has such a setting
     * ({@code ThinkingAndEffortAreConversationSettings_260917_oo01}).
     */
    public void setEffort(String effort) {
        provider.setEffort(effort);
    }

    /** @return what the provider was told, or {@code null} when it has no such setting */
    public String getEffort() {
        return provider.getEffort();
    }

    /**
     * Removes all entries from the conversation history -- both of them.
     *
     * <p>The record the screen shows and the context the next request is built from are separate
     * lists. Emptying only the first leaves a conversation that looks new and answers from memory
     * ({@code ForgetTheConversationOnBothSides_260917_oo01}).</p>
     */
    public void clearHistory() {
        conversationHistory.clear();
        historySnapshot.set(List.of());
        onProvider(p -> { p.clearHistory(); return null; });
        // New conversation: end the current I/O-log session and renumber turns from 1.
        if (ioLog != null) ioLog.resetSession(myChatName());
        ioTurn = 0;
    }

    /** @return the array the next request is built from, as JSON, or {@code null} if the provider keeps none */
    public String requestMessagesJson() {
        return onProvider(LlmProvider::requestMessagesJson);
    }

    /**
     * Replaces the array the next request is built from; the screen's record and the settings are
     * not touched, and one line on the screen says it happened
     * ({@code RequestMessagesControl_261018_oo01}).
     *
     * @return {@code null} when replaced, otherwise why it was refused
     */
    public String replaceRequestMessages(String json) {
        if (busy) return "busy: the conversation is answering; try again when it has finished";
        String refused = onProvider(p -> p.replaceRequestMessages(json));
        if (refused == null) {
            recordHistory("system", "The request history was replaced from outside.");
        }
        return refused;
    }

    /**
     * Calls the model once with exactly the given messages, outside this conversation, and records
     * the request body and the reply in the I/O log. Callable from any thread, also while a turn
     * is running: it reads no conversation state and changes none
     * ({@code RequestMessagesControl_261018_oo01}).
     */
    public com.scivicslab.chatui.core.provider.OneShotReply completeOneShot(
            String messagesJson, String model, Boolean noThink) {
        com.scivicslab.chatui.core.provider.OneShotReply reply =
                provider.completeFromMessages(messagesJson, model, noThink);
        if (ioLog != null && reply.requestBody() != null) {
            try {
                long session = ioLog.ensureSession(myChatName());
                StringBuilder m = new StringBuilder("REQUEST:\n")
                        .append(RequestBodyRecord.forLog(reply.requestBody()))
                        .append("\n\nRESPONSE:\n").append(reply.reply() == null ? "" : reply.reply());
                if (reply.error() != null) m.append("\n\nERROR:\n").append(reply.error());
                m.append("\n\nUSAGE: promptTokens=0 completionTokens=0");
                ioLog.record(session, "agent", "oneshot/" + System.currentTimeMillis(), m.toString());
            } catch (Exception e) {
                logger.log(Level.WARNING, "I/O log one-shot record failed", e);
            }
        }
        return reply;
    }

    // ---- Log ring buffer ----

    /**
     * Stores a log event in the ring buffer and forwards it to the SSE emitter if connected.
     *
     * @param level      the log level (e.g. "INFO", "WARNING")
     * @param loggerName the name of the originating logger
     * @param message    the log message text
     * @param timestamp  the event timestamp in epoch milliseconds
     */
    public void publishLog(String level, String loggerName, String message, long timestamp) {
        ChatEvent event = ChatEvent.log(level, loggerName, message, timestamp);
        logBuffer[logHead] = event;
        logHead = (logHead + 1) % LOG_BUFFER_SIZE;
        if (logCount < LOG_BUFFER_SIZE) logCount++;
        if (sseEmitter != null) {
            try { sseEmitter.accept(event); }
            catch (Exception ignored) {}
        }
    }

    /**
     * Registers an SSE emitter that receives real-time log events.
     *
     * @param emitter the consumer to receive log events
     */
    public void setSseEmitter(Consumer<ChatEvent> emitter) { this.sseEmitter = emitter; }
    /** Unregisters the current SSE emitter, stopping real-time log forwarding. */
    public void clearSseEmitter() { this.sseEmitter = null; }

    /**
     * Streams a chat event straight to the connected browser via the SSE emitter, without buffering
     * it in the log ring (unlike {@link #emitEvent}). Used for autonomous-turn output, which is chat
     * content rather than a log entry. The emitter ({@code SseConnection::emit}) is safe to call from
     * any thread, so this may be invoked from the managed thread pool during a drain.
     *
     * @param event the chat event to stream
     */
    private void emitToSse(ChatEvent event) {
        Consumer<ChatEvent> e = sseEmitter;
        if (e != null) {
            try { e.accept(event); }
            catch (Exception ignored) {}
        }
    }

    /**
     * Buffers a {@link ChatEvent} in the ring buffer and forwards it to the SSE emitter.
     *
     * <p>Used by the autonomous event monitor to emit events that arrive outside of a
     * user-prompted turn (e.g. ScheduleWakeup responses). Unlike {@link #publishLog}, this
     * method accepts a pre-built {@code ChatEvent} and does not wrap it in a log envelope.</p>
     *
     * @param event the event to buffer and emit
     */
    public void emitEvent(ChatEvent event) {
        logBuffer[logHead] = event;
        logHead = (logHead + 1) % LOG_BUFFER_SIZE;
        if (logCount < LOG_BUFFER_SIZE) logCount++;
        if (sseEmitter != null) {
            try { sseEmitter.accept(event); }
            catch (Exception ignored) {}
        }
    }

    /**
     * Returns the contents of the log ring buffer in chronological order.
     *
     * @return a list of the most recent log events (up to {@code LOG_BUFFER_SIZE})
     */
    public List<ChatEvent> getRecentLogs() {
        List<ChatEvent> result = new ArrayList<>(logCount);
        int start = (logHead - logCount + LOG_BUFFER_SIZE) % LOG_BUFFER_SIZE;
        for (int i = 0; i < logCount; i++) result.add(logBuffer[(start + i) % LOG_BUFFER_SIZE]);
        return result;
    }

    // ---- MCP result tracking ----

    /**
     * Registers a UUID so that getResultStatus() returns "processing" until the prompt completes.
     *
     * @param key the MCP result UUID to register as pending
     */
    public void registerPendingResultKey(String key) {
        pendingResultKeys.add(key);
    }

    /**
     * Stores the accumulated LLM response text for a completed MCP prompt.
     *
     * @param key  the MCP result UUID, previously registered via {@link #registerPendingResultKey(String)}
     * @param text the accumulated assistant response text
     */
    public void storeCompletedResult(String key, String text) {
        pendingResultKeys.remove(key);
        completedResults.put(key, text);
        logger.info("MCP result stored: key=" + key + " length=" + text.length());
    }

    /**
     * Returns the status of an MCP result key: "completed", "processing", or "unknown".
     * "unknown" means the key was never registered with this actor.
     *
     * @param key the MCP result UUID to query
     * @return "completed", "processing", or "unknown"
     */
    public String getResultStatus(String key) {
        if (completedResults.containsKey(key)) return "completed";
        if (pendingResultKeys.contains(key) || key.equals(activeResultKey)) return "processing";
        return "unknown";
    }

    /**
     * Returns the stored LLM response text for the given MCP result key, or null if not found.
     *
     * @param key the MCP result UUID to look up
     * @return the accumulated assistant response text, or {@code null} if not found
     */
    public String getCompletedResult(String key) {
        return completedResults.get(key);
    }

    /**
     * Returns how the turn behind a stored result ended, as the one line {@code ask_chat} appends.
     *
     * @param key the result UUID
     * @return the line, or {@code null} when the turn left none
     */
    public String getCompletedEnding(String key) {
        return completedEndings.get(key);
    }

    /**
     * One entry in the conversation history.
     *
     * @param role    the message role (e.g. "user" or "assistant")
     * @param content the message text
     */
    /**
     * One line of the conversation.
     *
     * @param role    {@code user}, {@code assistant} or {@code error}
     * @param content what was said
     * @param origin  for a user line, which of the four ways it arrived — see
     *                {@link #originOf(String)}; {@code null} on every other line
     * @param at      when the line was recorded, in milliseconds since the epoch; {@code null} for a
     *                line brought back from the I/O log, which does not keep the time
     * @param model   for an assistant line, the model that wrote it; {@code null} when not known
     * @param durationMs for an assistant line, how long the turn took; {@code null} when not known
     */
    public record HistoryEntry(String role, String content, String origin, Long at, String model, Long durationMs,
                               String turnId, String requestId) {
        public HistoryEntry(String role, String content, String origin, Long at, String model, Long durationMs) {
            this(role, content, origin, at, model, durationMs, null, null);
        }

        public HistoryEntry(String role, String content, String origin) {
            this(role, content, origin, null, null, null);
        }

        public HistoryEntry(String role, String content) {
            this(role, content, null);
        }
    }

    /**
     * Turns the queue's record of where a prompt came from into what a reader is shown.
     *
     * <p>A conversation takes prompts from four places, and read afterwards they look alike. One
     * of them is a person's own work being done for them by a workflow, which is the whole reason
     * the workflow exists, so the reader is told which ({@code PromptOriginOnScreen_260915_oo01}).
     * The screen says so itself; the REST endpoint cannot tell a program from a person, so a
     * prompt that claims nothing is shown as coming from a program.</p>
     *
     * @param source what {@code PromptQueue} was given: {@code screen}, {@code human},
     *               {@code agent:ask_chat:<name>}, {@code agent:workflow}, or anything else
     * @return the words to show
     */
    public static String originOf(String source) {
        if (source == null || source.isBlank() || "human".equals(source)) return "api";
        if ("screen".equals(source)) return "screen";
        if ("agent:workflow".equals(source)) return "workflow";
        // Put into the queue by this conversation itself, not by a person or another conversation
        // (TimekeeperPausesATurnForAnUrgentRequest_261107_oo01).
        if (isResume(source)) return "timekeeper resume";
        if (Timekeeper.SOURCE.equals(source)) return "timekeeper notice";
        // A project workflow's job telling this conversation something, e.g. the team it built
        // (TeamBuiltByProjectWorkflow_261108_oo01).
        if (source.startsWith("job:")) return "job " + source.substring("job:".length());
        if (source.startsWith(ASK_CHAT_REPLY_SOURCE_PREFIX)) {
            return "reply from chat " + source.substring(ASK_CHAT_REPLY_SOURCE_PREFIX.length());
        }
        if (source.startsWith(ASK_CHAT_SOURCE_PREFIX)) {
            String asker = source.substring(ASK_CHAT_SOURCE_PREFIX.length());
            // A conversation's actor name is "<project>/chat-<id>"; anything else asking through
            // the same path is a plan running as a job.
            return (asker.matches("[^/]+/chat-[^./]+") ? "chat " : "workflow ") + asker;
        }
        return source;
    }

    /** What {@code AskChatTool} puts in front of the name of whoever asked. */
    public static final String ASK_CHAT_SOURCE_PREFIX = "agent:ask_chat:";

    /**
     * The source a reply to a model's {@code ask_chat} is queued under, followed by the conversation
     * that replied ({@code AskChatWithoutWaiting_261022_oo01}).
     */
    public static final String ASK_CHAT_REPLY_SOURCE_PREFIX = "agent:reply:";
}
