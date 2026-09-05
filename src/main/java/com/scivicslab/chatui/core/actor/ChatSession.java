package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.agent.ContextBudget;
import com.scivicslab.chatui.agent.DocSearchTool;
import com.scivicslab.chatui.agent.FetchTool;
import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.FileReadTool;
import com.scivicslab.chatui.agent.FileWriteTool;
import com.scivicslab.chatui.agent.LoadSkillTool;
import com.scivicslab.chatui.agent.ReferenceLinkTool;
import com.scivicslab.chatui.agent.RunPlanTool;
import com.scivicslab.chatui.agent.SetCollaboratorTool;
import com.scivicslab.chatui.agent.SetWorkflowTool;
import com.scivicslab.chatui.agent.TextToolCallParser;
import com.scivicslab.chatui.agent.ToolCall;
import com.scivicslab.chatui.agent.WebSearchTool;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.core.service.AuthMode;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.examples.jshell.JShellCalculator;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POJO owning the entire state of one conversation tab's chat session.
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

    private final LlmProvider provider;
    private final AuthMode authMode;

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
     *  another tab — distinct from {@link #watchdogName} (an unrelated, unported StallMonitor field). */
    private ActorRef<CallWatchdog> watchdogRef;
    /** The shared {@link CollaborationGraph}, consulted (and updated) by the {@code set_collaborator}
     *  tool and by babysitter-loop methods resolving a role (e.g. {@code "worker"}) to a chat id. */
    private ActorRef<CollaborationGraph> collaborationGraphRef;
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
    // UUIDs that have been registered (via submitPrompt) but not yet started processing
    private final Set<String> pendingResultKeys = new HashSet<>();
    // UUID of the prompt currently being processed, or null
    private String activeResultKey;

    // ---- Agent loop (ChatSessionAgentLoop_260823_oo01): start -> (stepExpectingAction -> runTool)* -> finish ----
    /**
     * Steps allowed when the workflow's step-limit transition names no number
     * ({@code TurnResourceLimits_260830_oo01}). Not the limit itself: the limit is what the
     * workflow says. This value exists so that a workflow installed by {@code set_workflow} that
     * forgot its step-limit transition still stops, instead of running to
     * {@code Interpreter.runUntilEnd}'s 10000-iteration backstop.
     */
    private static final int DEFAULT_STEP_LIMIT = 30;
    /** When the running turn began, for the duration reported with its answer. */
    private long turnStartedAt;
    /** Ceiling on the observation size a workflow may ask for; set from configuration. */
    private int maxObservationChars = ContextBudget.OBS_THRESHOLD;
    /**
     * The range of the file system this conversation's {@code read}/{@code write} may touch. The
     * default keeps both confined to the directory the process was started in; the generating side
     * replaces it with the configured range ({@code FileAccessScope_260830_oo01}).
     */
    private FileAccessScope fileScope = FileAccessScope.processDirectory();

    private static final String SYSTEM_PROMPT = """
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

            Available tools:
            - read(path): read a file, or a whole directory recursively, under the working directory.
              To read several files at once, put one path per line in the same "path" parameter —
              use that instead of reading a parent directory when you want three specific files,
              since the parent brings everything else under it along too. A read that would return
              more than 4,000,000 characters or 1,000 files stops early and says where it stopped,
              listing the paths it did not reach so you can ask for them separately.
            - calc(expression): evaluate a Java arithmetic expression, e.g. 23*47 or Math.sqrt(16).
            - web_search(query): search the web and fetch the top results' page content.
            - fetch(url): fetch one specific URL you already have and return its readable text.
            - search_docs(query): search this team's internal documentation. It returns a ranked
              list of CANDIDATE documents — title, id, source path and a short summary each — not
              the answer to your question. A summary says what a document is about, not what it
              says, so to actually answer you must then call read on the "path" of the candidates
              that look relevant, and read more than one when the summaries do not settle which is
              right. Answering straight from a summary, or from a single top-ranked candidate you
              did not open, is how you get the answer wrong. Three unrelated searches — over words,
              over term frequencies, over meaning — always run, and their results are merged; each
              candidate says which of them found it, and one found by more than one is the strongest
              signal in the list. Search in the language the documents are written in: these are
              mostly Japanese.
            - list_references(id, direction, relation): follow the reference links an author
              declared between documents. Pass the "id" a search_docs candidate printed. Direction
              "forward" (the default) returns the documents that one refers to; "backward" returns
              the documents that refer to it. Each edge has a kind, written by the author — the
              kinds are not a fixed list, so read the kind that comes back rather than expecting a
              particular word; pass "relation" to keep only one kind, or omit it for every kind.
              This is not a search: the relation is written in the document's own source, so it is
              the way to reach a document whose wording never matches the question — the value you
              need may be stated only in a document its author told you to read first. It returns
              the same kind of candidate list search_docs does, so call read on a path to see what
              a document actually says.
            - write(path, content): save text to a file under the working directory. Requires TWO
              <parameter> tags in the same invoke block: one named "path", one named "content".
            - ask_chat(chatId, prompt, timeoutSeconds): send an instruction to another conversation
              (e.g. "02" for one in your own project, or "project2/02" to reach one in another
              project) and wait for its reply. Requires "chatId" and "prompt" <parameter>
              tags; "timeoutSeconds" is an optional third <parameter> tag (default 60) — pass a
              larger value if you expect the target to take a while, e.g. because it will itself
              call ask_chat on another tab. Use this to direct or review another tab's work.
            - set_workflow(chatId, yaml): replace another conversation tab's agent-loop workflow
              with the given Turing-workflow YAML text (not a file path). Requires TWO <parameter>
              tags: "chatId" and "yaml". Use this to author a workflow for another tab to run,
              then use ask_chat to actually kick off a turn under it.
            - run_plan(yaml, timeoutSeconds): run a plan you wrote — a Turing-workflow YAML whose
              steps drive other conversations — and wait for its result. Requires a "yaml"
              <parameter> tag; "timeoutSeconds" is optional. Each step is an action on "this" with
              method askChat and two arguments, the target conversation's full name
              ("project1/chat-02") and the prompt to send; end the plan with method finish, and give
              every asking state a fallback transition to "end" with method reportFailure. The first
              state must be named "0". Use this when a task needs several conversations driven in a
              fixed order, rather than you asking each one yourself.
            - load_skill(name): read one of the skills listed after this tool list in full — its
              step-by-step instructions, the routes it says to call, its own files. Requires a
              "name" <parameter> tag. Each skill's description says when it applies; when one
              applies to the task at hand, load it before you act rather than after.
            - set_collaborator(chatId, role, collaboratorChatId): record that, for tab "chatId",
              the tab playing role "role" (e.g. "worker") is "collaboratorChatId". Requires THREE
              <parameter> tags: "chatId", "role", "collaboratorChatId". A workflow installed via
              set_workflow can then resolve that role instead of a hardcoded chat id, and you can
              reassign it again later by calling this a second time.

            Call at most one tool per reply. After a tool result comes back, either call another tool \
            or give your final answer. When you have enough information, answer in plain text with NO \
            <invoke> block — that plain text is taken as your final answer to the user.""";

    // Per-turn working memory, reset in start(). Not thread-confined by field type (see
    // ChatSessionAgentLoop_260823_oo01's own note in chat-session-agent-loop.yaml) — safe only
    // because runUntilEnd() is always invoked as a plain method from within an existing tell()
    // closure, so the whole turn runs on this actor's own single thread.
    private String question;
    private String pendingPrompt;
    private String turnModel;
    private Consumer<ChatEvent> turnEmitter;
    private ActorRef<ChatSession> turnSelf;
    private CompletableFuture<Void> turnDone;
    private boolean turnNoThink;
    /** Open I/O-log session id for this turn, or -1 when logging is disabled. Set in start(). */
    private long ioSession = -1;
    /** This turn's number, for turn{N}/step{M}/... I/O-log labels. Set in start(). */
    private int ioTurnNo;
    private List<ToolCall> pendingCalls;
    private String finalAnswer;
    private int stepCount;
    private volatile boolean cancelled;

    // ---- 型3: 文書検索の状態機械 (DocRetrievalAgentLoop_260830_oo01) ------------------
    // The default agent loop leaves every decision — search again? read what? answer now? — inside
    // one LLM reply. These methods split that into states, so the order is written in the YAML and
    // the number of searches and documents read are its arguments.

    /**
     * Turns the turn's question into search terms and runs one search. Entry state of
     * {@code doc-retrieval-loop.yaml}.
     *
     * @param args unused
     * @return {@link ActionResult} with {@code success=true} iff the search returned candidates
     */
    public ActionResult searchDocs(String args) {
        String query = askLlm("Write the search terms for finding, in this team's internal "
                + "documentation, what answers the question below. Reply with the terms only — no "
                + "explanation, no quotes. Write them in the language the documents are written in "
                + "(these are mostly Japanese).\n\nQuestion:\n" + question);
        return runSearch(query);
    }

    /** Runs one search and keeps what came back. */
    private ActionResult runSearch(String query) {
        if (query == null || query.isBlank()) {
            return new ActionResult(false, "no search terms");
        }
        lastQuery = query.strip();
        searchCount++;
        lastHits = DocSearchTool.search(lastQuery, 0);
        boolean found = lastHits != null && !lastHits.startsWith("No documents found");
        // Written to the I/O log in the same shape the default loop uses for a tool call, so the
        // same reading of the log measures both loops (DocRetrievalBenchmark_260830_oo01).
        stepCount++;
        recordToolIo(new ToolCall("search-" + searchCount, "search_docs",
                new JSONObject().put("query", lastQuery).toString()), lastHits);
        return new ActionResult(found, found ? "candidates returned" : "nothing found");
    }

    /**
     * Judges whether the candidate list holds something that can answer the question. Makes one LLM
     * call and keeps its reasoning for {@link #refineQueryAndSearch()}.
     *
     * @param args unused
     * @return {@link ActionResult} with {@code success=true} iff the list looks sufficient
     */
    public ActionResult judgeHitsSufficient(String args) {
        if (lastHits == null) return new ActionResult(false, "no candidates yet");
        String verdict = askLlm("Below is a question and a list of candidate documents returned by "
                + "a search. Decide whether any of them is likely to contain the answer. Judge from "
                + "the titles and summaries; you cannot open them here.\n\nIf at least one candidate "
                + "is likely to answer it, reply with exactly:\nENOUGH\nOtherwise reply with:\n"
                + "MISSING: <what the search failed to find, in one sentence>\n\nQuestion:\n"
                + question + "\n\nCandidates:\n" + lastHits);
        boolean enough = verdict != null && verdict.strip().toUpperCase().startsWith("ENOUGH");
        hitsShortfall = enough ? null : verdict;
        return new ActionResult(enough, enough ? "candidates look sufficient" : "candidates look thin");
    }

    /**
     * The searching state's give-up guard.
     *
     * @param limit how many searches this turn may run; blank falls back to 3
     * @return {@link ActionResult} with {@code success=true} iff that many have been run
     */
    public ActionResult searchLimitReached(String limit) {
        int max = parsePositiveOr(limit, 3);
        boolean reached = searchCount >= max;
        return new ActionResult(reached, reached ? "search limit " + max + " reached"
                                                 : searchCount + "/" + max + " searches used");
    }

    /**
     * The searching state's catch-all, reached once judging and the limit guard have both failed.
     * Keeps what is missing; makes no LLM call.
     *
     * @param args unused
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult judgeHitsNeedRefinement(String args) {
        if (hitsShortfall == null) hitsShortfall = "the candidates do not answer the question";
        return new ActionResult(true, "refinement requested");
    }

    /**
     * Writes different search terms and searches again, told what the previous attempt missed and
     * what it already tried.
     *
     * @param args unused
     * @return {@link ActionResult} with {@code success=true} iff the new search returned candidates
     */
    public ActionResult refineQueryAndSearch(String args) {
        String query = askLlm("A search of this team's internal documentation did not find what was "
                + "needed. Write different search terms. Do not repeat the previous ones. Reply with "
                + "the terms only. Write them in the language the documents are written in (these "
                + "are mostly Japanese).\n\nQuestion:\n" + question
                + "\n\nPrevious terms:\n" + lastQuery
                + "\n\nWhat was missing:\n" + hitsShortfall);
        return runSearch(query);
    }

    /**
     * Opens the candidates worth reading. The model chooses which — it has the titles and summaries
     * — and this method reads them, so choosing stays a judgement and reading stays mechanical.
     *
     * @param howMany how many documents to open at most; blank falls back to 3
     * @return {@link ActionResult} with {@code success=true} iff at least one document was read
     */
    public ActionResult readSources(String howMany) {
        int max = parsePositiveOr(howMany, 3);
        String picked = askLlm("Below is a question and a list of candidate documents. Choose the "
                + "ones worth opening to answer it — at most " + max + ". Reply with their \"path\" "
                + "values, one per line, and nothing else.\n\nQuestion:\n" + question
                + "\n\nCandidates:\n" + lastHits);

        StringBuilder read = new StringBuilder();
        int opened = 0;
        for (String line : (picked == null ? "" : picked).split("\n")) {
            if (opened >= max) break;
            String path = line.strip().replaceAll("^[-*0-9.\\s]+", "");
            if (path.isEmpty() || !path.startsWith("/")) continue;
            String text = FileReadTool.read(fileScope, path);
            if (text.startsWith("error:")) {
                logToTab("INFO", "readSources: " + text);
                continue;
            }
            read.append("===== ").append(path).append(" =====\n").append(text).append("\n\n");
            stepCount++;
            recordToolIo(new ToolCall("read-" + opened, "read",
                    new JSONObject().put("path", path).toString()), text);
            opened++;
        }
        readSourcesText = read.toString();
        return new ActionResult(opened > 0, opened + " document(s) read");
    }

    /**
     * The reading state's catch-all: nothing could be opened, so say that instead of leaving the
     * turn stuck.
     *
     * @param args unused
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult reportRetrievalFailure(String args) {
        finalAnswer = "I could not open any document that answers this. The search terms I tried "
                + "were: " + lastQuery + ".";
        return new ActionResult(true, "retrieval failure reported");
    }

    /**
     * Answers from what was opened, and from nothing else. When nothing was opened (the give-up
     * path), answers from the candidate list and says so.
     *
     * @param args unused
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult answerFromSources(String args) {
        boolean haveSources = readSourcesText != null && !readSourcesText.isBlank();
        String prompt = haveSources
                ? "Answer the question using only the documents below. State the name of the "
                  + "document the answer came from. If they do not contain the answer, say so.\n\n"
                  + "Question:\n" + question + "\n\nDocuments:\n" + readSourcesText
                : "The search did not find documents that answer the question, and none were "
                  + "opened. Answer as best you can from the candidate list below, and say plainly "
                  + "that you could not confirm it against a document.\n\nQuestion:\n" + question
                  + "\n\nCandidates:\n" + lastHits;
        finalAnswer = askLlm(prompt);
        return new ActionResult(true, "answered");
    }

    /**
     * Checks the draft answer against the question: does it state everything that was asked? Reading
     * the right document and stating everything it says are different things — measured over 30
     * questions, three of the five the state machine got wrong had opened the right document and
     * left part of the answer out ({@code DocRetrievalBenchmark_260830_oo01}).
     *
     * @param args unused
     * @return {@link ActionResult} with {@code success=true} iff nothing asked for is missing
     */
    public ActionResult answerComplete(String args) {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return new ActionResult(false, "no draft answer");
        }
        String verdict = askLlm("Below is a question and an answer to it. Check only one thing: does "
                + "the answer state everything the question asked for? A question that asks for two "
                + "things needs both; a question that asks for a name and a reason needs both.\n\n"
                + "If nothing asked for is missing, reply with exactly:\nCOMPLETE\nOtherwise reply "
                + "with:\nMISSING: <what was asked for and not stated, in one sentence>\n\n"
                + "Question:\n" + question + "\n\nAnswer:\n" + finalAnswer);
        boolean complete = verdict != null && verdict.strip().toUpperCase().startsWith("COMPLETE");
        answerShortfall = complete ? null : verdict;
        return new ActionResult(complete, complete ? "answer covers the question" : "answer is short");
    }

    /**
     * The verifying state's give-up guard: keep the draft as it stands.
     *
     * @param limit how many rewrites this turn may make; blank falls back to 2
     * @return {@link ActionResult} with {@code success=true} iff that many have been made
     */
    public ActionResult answerLimitReached(String limit) {
        int max = parsePositiveOr(limit, 2);
        boolean reached = rewriteCount >= max;
        return new ActionResult(reached, reached ? "rewrite limit " + max + " reached"
                                                 : rewriteCount + "/" + max + " rewrites used");
    }

    /**
     * The verifying state's catch-all, reached once the check has failed and rewrites remain.
     * Keeps what is missing; makes no LLM call.
     *
     * @param args unused
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult answerNeedsMore(String args) {
        if (answerShortfall == null) answerShortfall = "part of what was asked is not stated";
        return new ActionResult(true, "rewrite requested");
    }

    /**
     * Writes the answer again from the same documents, told what the previous draft left out.
     *
     * @param args unused
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult rewriteAnswer(String args) {
        rewriteCount++;
        String rewritten = askLlm("Your previous answer left something out. Write it again, from the "
                + "documents below and nothing else, stating everything the question asks for. Keep "
                + "what was already right.\n\nQuestion:\n" + question
                + "\n\nWhat was missing:\n" + answerShortfall
                + "\n\nYour previous answer:\n" + finalAnswer
                + "\n\nDocuments:\n" + readSourcesText);
        if (rewritten != null && !rewritten.isBlank()) finalAnswer = rewritten;
        return new ActionResult(true, "answer rewritten");
    }

    /**
     * One blocking LLM call whose whole reply is returned. Streams to the turn's emitter as
     * "thinking", like {@link #callJudgeLlm}.
     *
     * @param prompt what to send
     * @return the reply, or {@code ""} if the call failed
     */
    private String askLlm(String prompt) {
        StringBuilder buf = new StringBuilder();
        ProviderContext ctx = new ProviderContext(apiKey, List.of(), turnNoThink, () -> {});
        try {
            providerRef().ask(p -> {
                p.sendPrompt(prompt, turnModel, event -> {
                    if ("delta".equals(event.type()) && event.content() != null) {
                        buf.append(event.content());
                        turnEmitter.accept(ChatEvent.thinking(event.content()));
                    }
                }, ctx);
                return null;
            }, system.getManagedThreadPool()).get();
        } catch (Exception e) {
            logger.log(Level.WARNING, "LLM call failed", e);
            return "";
        }
        return buf.toString();
    }

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

    // ---- 型3: 文書検索を状態機械にする (DocRetrievalAgentLoop_260830_oo01) ----
    // Per-turn, reset in start(). The loop is search -> judge -> (refine -> judge)* -> read -> answer,
    // so each state's result has to outlive the transition that produced it.
    /** The candidate list search_docs returned, as the model sees it. */
    private String lastHits;
    /** The search terms last used, so a refinement can say how it differs. */
    private String lastQuery;
    /** Searches run in this turn, counted against the workflow's limit. */
    private int searchCount;
    /** What judgeHitsSufficient said was missing, consumed by refineQueryAndSearch. */
    private String hitsShortfall;
    /** The text of every document opened in this turn, in the order they were opened. */
    private String readSourcesText;
    /** What the verification said the draft answer was missing, consumed by rewriteAnswer. */
    private String answerShortfall;
    /** Rewrites used in this turn, counted against the workflow's limit. */
    private int rewriteCount;

    // ---- Prompt construction (ChatSessionPorting_260823_oo01 2-b-i) ----
    // stepExpectingAction() delegates building the text it sends to provider.sendPrompt() to a
    // swappable sub-workflow (Interpreter.call(...)), rather than constructing it directly. The
    // sub-workflow pushes one or more constructed prompts via appendConstructedPrompt(); this
    // queue is drained one prompt per stepExpectingAction() call, so a sub-workflow that splits
    // one turn's input into N prompts (e.g. N checklist items) sends them as N separate LLM
    // turns instead of one combined call.
    private final Deque<String> constructedPrompts = new ArrayDeque<>();
    /** Classpath workflow file used to build each step's prompt(s); swappable via the setter. */
    private String promptWorkflowFile = "prompt-construction-default.yaml";

    /** Lazily created on first {@code calc} tool call; kept for this session's lifetime. */
    private JShellCalculator calculator;

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
        this.provider = provider;
        this.ioLog = ioLog;

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

    // ---- Wiring — the generating side (ConversationTab) sets these; ChatSession creates none of them ----

    /**
     * Makes {@code system.getActor(...)} resolvable from inside this session's own methods.
     * The generating side sets this once, right after {@code createChild}.
     *
     * @param system the actor system this session's ActorRef is registered in
     */
    public void setActorSystem(IIActorSystem system) { this.system = system; }

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
    }

    /** @return this session's qualified conversation name, e.g. {@code "project1/chat-01"} */
    private String myChatName() { return ChatUiActorSystem.chatActorName(projectId, chatId); }

    /** @param watchdogRef the shared {@link CallWatchdog}, for the {@code ask_chat} tool */
    public void setWatchdogRef(ActorRef<CallWatchdog> watchdogRef) { this.watchdogRef = watchdogRef; }

    /** @param collaborationGraphRef the shared {@link CollaborationGraph} */
    public void setCollaborationGraphRef(ActorRef<CollaborationGraph> collaborationGraphRef) {
        this.collaborationGraphRef = collaborationGraphRef;
    }

    /** @param fileScope the range of the file system {@code read}/{@code write} may touch */
    public void setFileScope(FileAccessScope fileScope) {
        this.fileScope = fileScope;
    }

    /** @param skillRegistryRef the shared {@link SkillRegistry}, for {@code load_skill} */
    public void setSkillRegistryRef(ActorRef<SkillRegistry> skillRegistryRef) {
        this.skillRegistryRef = skillRegistryRef;
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
     * Forwards one entry to this session's tab log multiplexer ({@code <projectId>/chat-<chatId>.log}), in
     * addition to (not instead of) the existing {@code logger.xxx(...)} calls near each call site —
     * those keep flowing to {@link com.scivicslab.chatui.logging.LogTap} unchanged
     * ({@code 150_TabScopedLogging_260826_oo01} "既存のLOG.xxx()を置き換えず"). Silently no-ops if
     * the actor system or tab id isn't wired yet, or the tab log actor isn't found.
     */
    private void logToTab(String type, String message) {
        if (system == null || chatId == null) return;
        try {
            IIActorRef<?> tabLog = system.getIIActor(myChatName() + ".log");
            if (tabLog == null) return;
            JSONObject args = new JSONObject();
            args.put("source", "ChatSession");
            args.put("type", type);
            args.put("data", message);
            tabLog.callByActionName("add", args.toString());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to forward log entry to tab log", e);
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
        if (input.trim().toLowerCase().startsWith("/clear")) {
            conversationHistory.clear();
            historySnapshot.set(List.of());
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
     * @param done   completed once the prompt has finished processing (success or error)
     */
    public void startPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                            ActorRef<ChatSession> self, CompletableFuture<Void> done) {
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
     * @param done      completed once the prompt has finished processing (success or error)
     * @param resultKey UUID under which the accumulated response is stored for later retrieval via
     *                  {@link #getCompletedResult(String)}, or {@code null} for human-typed prompts
     */
    public void startPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                            ActorRef<ChatSession> self, CompletableFuture<Void> done,
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
     * @param done      completed once the prompt has finished processing (success or error)
     * @param resultKey UUID under which the accumulated response is stored for later retrieval via
     *                  {@link #getCompletedResult(String)}, or {@code null} for human-typed prompts
     * @param noThink   whether to ask the provider to skip its reasoning/thinking phase
     */
    public void startPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                            ActorRef<ChatSession> self, CompletableFuture<Void> done,
                            String resultKey, boolean noThink) {
        if (busy) {
            emitter.accept(ChatEvent.error("Already processing a prompt. Please wait or cancel."));
            done.complete(null);
            return;
        }
        if (!isAuthenticated()) {
            emitter.accept(ChatEvent.error(
                    "No authentication configured. Please provide an API key."));
            done.complete(null);
            return;
        }

        busy = true;
        recordHistory("user", prompt);
        // Open (lazily) the conversation's I/O-log session and number this turn for the Sessions tab.
        final long ioSession = (ioLog != null) ? ioLog.ensureSession(myChatName()) : -1;
        final int ioTurnNo = ++ioTurn;
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
     * @param done    completed to signal the prompt has finished processing
     * @param self    this actor's own reference, forwarded to {@code PromptQueue} to dispatch the next prompt
     */
    public void onPromptComplete(Consumer<ChatEvent> emitter, CompletableFuture<Void> done, ActorRef<ChatSession> self) {
        busy = false;
        activeResultKey = null;

        ActorRef<PromptQueue> promptQueue = getPromptQueue();
        if (promptQueue != null) {
            promptQueue.tell(q -> q.onPromptComplete(self));
        }

        emitter.accept(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), false));
        done.complete(null);
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
     * @param done      completed once the turn has finished processing (success or error)
     * @param resultKey UUID under which the final answer is stored for later retrieval via
     *                  {@link #getCompletedResult(String)}, or {@code null} for human-typed prompts
     * @param noThink   whether to ask the provider to skip its reasoning/thinking phase for
     *                  every LLM call in this turn (see {@link #stepExpectingAction()})
     */
    public void start(String prompt, String model, Consumer<ChatEvent> emitter,
                       ActorRef<ChatSession> self, CompletableFuture<Void> done, String resultKey,
                       boolean noThink) {
        if (busy) {
            emitter.accept(ChatEvent.error("Already processing a prompt. Please wait or cancel."));
            done.complete(null);
            return;
        }
        if (!isAuthenticated()) {
            emitter.accept(ChatEvent.error("No authentication configured. Please provide an API key."));
            done.complete(null);
            return;
        }

        busy = true;
        recordHistory("user", prompt);
        if (resultKey != null) {
            pendingResultKeys.remove(resultKey);
            activeResultKey = resultKey;
        }

        this.question = prompt;
        this.pendingPrompt = prompt;
        this.turnModel = model;
        this.turnEmitter = emitter;
        this.turnSelf = self;
        this.turnDone = done;
        this.turnNoThink = noThink;
        this.ioSession = (ioLog != null) ? ioLog.ensureSession(myChatName()) : -1;
        this.ioTurnNo = ++ioTurn;
        this.turnStartedAt = System.currentTimeMillis();
        this.pendingCalls = null;
        this.finalAnswer = null;
        this.stepCount = 0;
        this.cancelled = false;
        this.constructedPrompts.clear();
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
        constructedPrompts.addLast(prompt);
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
        String text = (stepCount == 1) ? (firstStepPrompt() + "\n\n" + pendingPrompt) : pendingPrompt;
        return new ActionResult(true, text == null ? "" : text);
    }

    /**
     * The turn's opening text: the fixed tool instructions, then the two things that vary between
     * conversations — the skill catalog and this project's instructions
     * ({@code SkillAndAgentsFile_260830_oo01}). Both are assembled here rather than compiled into
     * {@link #SYSTEM_PROMPT} because both are read from files that can change while the system runs.
     *
     * @return the text prefixed to this turn's first prompt
     */
    private String firstStepPrompt() {
        StringBuilder buf = new StringBuilder(SYSTEM_PROMPT);
        buf.append("\n\nread may read files under: ").append(fileScope.describeReadRoots())
           .append("\nwrite may only write under: ").append(fileScope.writeRoot())
           .append("\nA relative path is taken from the write directory. When a skill's text points"
                 + " at a file in its own directory, read it — that directory is readable.");
        String catalog = skillCatalogText();
        if (!catalog.isEmpty()) {
            buf.append("\n\n").append(catalog);
        }
        if (projectInstructions != null && !projectInstructions.isBlank()) {
            buf.append("\n\nInstructions for this project, from its working directory. They govern"
                    + " the work you do here:\n\n").append(projectInstructions);
        }
        return buf.toString();
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
        String promptToSend = constructedPrompts.pollFirst();
        ProviderContext ctx = new ProviderContext(apiKey, List.of(), turnNoThink, () -> {});
        StringBuilder assistantBuf = new StringBuilder();
        // The reasoning is kept for the record, not for the answer: it goes to the I/O log's
        // REASONING: section and never into assistantBuf, which is what the next step parses for
        // tool calls and what becomes the turn's answer.
        StringBuilder thinkingBuf = new StringBuilder();
        ActorRef<LlmProvider> providerRef = providerRef();

        // The tab log used to receive nothing between here and recordStepIo() below, so a step
        // spent entirely on a thinking model's reasoning left the System Log tab blank for as long
        // as the model took. Announce the call before making it, then report progress while the
        // reply streams, so the log shows that the step is alive and how far it has got.
        String stepLabel = "turn" + ioTurnNo + "/step" + stepCount + "/llm";
        logToTab("INFO", stepLabel + " start: model=" + (turnModel == null ? "(server default)" : turnModel)
                + ", promptChars=" + promptToSend.length());
        AtomicLong streamedChars = new AtomicLong();
        AtomicLong progressLoggedAt = new AtomicLong(System.currentTimeMillis());

        try {
            providerRef.ask(p -> {
                // Relabel the provider's own "delta"/"result" as "thinking" / (dropped): this step
                // might still be a tool call, not the final answer, so its streamed text goes to the
                // browser's live trace, not the answer bubble — only finish() emits the browser's
                // "delta"/"result" for the answer, exactly once, with the confirmed final text.
                // Forwarding the provider's raw per-step "delta"/"result" here would (a) show
                // intermediate steps' raw <invoke> text as if it were the answer, and (b) show the
                // final step's answer text twice (once streamed here, once from finish()) — both
                // observed live before this fix.
                Consumer<ChatEvent> wrapped = event -> {
                    if ("delta".equals(event.type()) && event.content() != null) {
                        assistantBuf.append(event.content());
                        noteStreamProgress(stepLabel, streamedChars, progressLoggedAt, event.content());
                        turnEmitter.accept(ChatEvent.thinking(event.content()));
                    } else if ("result".equals(event.type())) {
                        // The provider's own per-call completion signal; the agent loop's real
                        // completion signal is finish()'s result event, emitted once for the turn.
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
            }, system.getManagedThreadPool()).get();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Agent-loop LLM call failed", e);
            turnEmitter.accept(ChatEvent.error("Error: " + e.getMessage()));
            finalAnswer = null;
            return new ActionResult(false, "error: " + e.getMessage());
        }

        String text = assistantBuf.toString();
        List<ToolCall> calls = TextToolCallParser.parse(text);
        recordStepIo(promptToSend, text, thinkingBuf.toString(), calls);
        if (!calls.isEmpty()) {
            pendingCalls = calls;
            for (ToolCall tc : calls) {
                turnEmitter.accept(ChatEvent.thinking("\n→ " + tc.name() + "(" + tc.argumentsJson() + ")\n"));
            }
            return new ActionResult(true, "action");
        }

        finalAnswer = text.trim();
        return new ActionResult(false, "final");
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
    private void recordConversationIo(String askedQuestion, String givenAnswer) {
        if (ioLog == null || ioSession < 0) return;
        if (askedQuestion == null || givenAnswer == null) return;
        try {
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/conversation",
                    CONVERSATION_QUESTION_MARKER + "\n" + askedQuestion
                            + "\n\n" + CONVERSATION_ANSWER_MARKER + "\n" + givenAnswer);
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

    /** Shortest gap between two streaming-progress lines in one step's tab log. */
    private static final long STREAM_PROGRESS_INTERVAL_MS = 1000L;

    /**
     * Adds one streamed chunk to this step's running total and writes a progress line to the tab
     * log, at most once per {@link #STREAM_PROGRESS_INTERVAL_MS}. The throttle is what keeps this
     * usable: a chunk is often a single token, and one log line per token would bury every other
     * entry in the System Log tab and make the tab log actor the bottleneck of the turn.
     *
     * <p>Runs on whichever thread the provider streams on, not this actor's thread. That is safe
     * because the counters are atomic and {@code logToTab} only enqueues into the tab log actor's
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
        logToTab("INFO", stepLabel + " streaming: " + total + " chars");
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
        if (ioLog == null || ioSession < 0) return;
        try {
            String requestJson = new org.json.JSONObject()
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
            m.append("\n\nUSAGE: promptTokens=0 completionTokens=0");
            ioLog.record(ioSession, "agent", "turn" + ioTurnNo + "/step" + stepCount + "/llm", m.toString());
            logToTab("INFO", "turn" + ioTurnNo + "/step" + stepCount + "/llm done: "
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
        if (reached && finalAnswer == null) {
            finalAnswer = "(step limit of " + max + " reached)";
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
        if (pendingCalls == null || pendingCalls.isEmpty()) {
            return new ActionResult(true, "observed");
        }
        StringBuilder observations = new StringBuilder();
        for (ToolCall tc : pendingCalls) {
            String fullObservation;
            try {
                fullObservation = executeTool(tc);
            } catch (Exception e) {
                fullObservation = "error: " + e.getMessage();
            }
            String forModel = ContextBudget.truncateObservation(fullObservation,
                    Math.min(parsePositiveOr(observationChars, ContextBudget.OBS_THRESHOLD),
                             maxObservationChars));
            recordToolIo(tc, fullObservation);
            turnEmitter.accept(ChatEvent.thinking("Observation (" + tc.name() + "): "
                    + forModel.substring(0, Math.min(200, forModel.length())) + "\n"));
            observations.append("Tool result (").append(tc.name()).append("):\n")
                    .append(forModel).append("\n\n");
        }
        pendingCalls = null;
        pendingPrompt = observations.toString().stripTrailing();
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
            logToTab("INFO", "turn" + ioTurnNo + "/step" + stepCount + "/tool: " + tc.name());
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log tool record failed", e);
        }
    }

    /**
     * Emits the final answer (if any), commits it to history, stores it under the turn's
     * {@code resultKey} if any, and runs the same completion hand-off as {@link #onPromptComplete}.
     *
     * @return {@link ActionResult} with {@code success=true}
     */
    public ActionResult finish() {
        if (!cancelled && finalAnswer != null) {
            String answer = finalAnswer;
            recordHistory("assistant", answer);
            recordConversationIo(question, answer);
            if (activeResultKey != null) {
                storeCompletedResult(activeResultKey, answer);
            }
            turnEmitter.accept(ChatEvent.delta(answer));
            // The browser puts what this event carries under the answer, as quarkus-chat-ui does.
            // Cost stays 0: a local model bills nothing, and the browser hides the field unless it
            // is above zero, rather than showing a made-up figure.
            turnEmitter.accept(ChatEvent.result(auditSessionId(), 0.0,
                    System.currentTimeMillis() - turnStartedAt, provider.getCurrentModel(), false));
        }
        // The turn's step-by-step messages — each carrying that step's whole observation — are of
        // no further use once the answer exists, and they crowd out earlier turns in the provider's
        // fixed-size history (TurnResourceLimits_260830_oo01). Replace them with what a later turn
        // actually needs: what was asked, and what was answered.
        try {
            providerRef().tell(p -> p.collapseTurn(question, finalAnswer));
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
     * {@link CollaborationGraph} and sends it {@code instruction}. Resets the redo budget, so each
     * phase gets its own (a phase that used up its redos does not starve the next one).
     *
     * @param instruction what to ask the worker to do in this phase
     * @return {@link ActionResult} with {@code success=true} iff a worker was resolved and replied
     */
    public ActionResult requestFromWorker(String instruction) {
        redoCount = 0;
        lastHits = null;
        lastQuery = null;
        searchCount = 0;
        hitsShortfall = null;
        readSourcesText = null;
        answerShortfall = null;
        rewriteCount = 0;
        redoNote = null;
        String workerChatId = resolveWorker();
        if (workerChatId == null) {
            lastCollaborationError = "no collaborator with role 'worker' set for " + myChatName()
                    + " (use set_collaborator first)";
            return new ActionResult(false, lastCollaborationError);
        }
        String prompt = (instruction == null || instruction.isBlank()) ? pendingPrompt : instruction;
        String reply = AskChatTool.askQualified(system, watchdogRef, myChatName(), workerChatId, prompt,
                WORKER_WAIT_TIMEOUT_SECONDS);
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
        String workerChatId = resolveWorker();
        if (workerChatId == null) {
            lastCollaborationError = "no collaborator with role 'worker' set for " + myChatName()
                    + " (use set_collaborator first)";
            return new ActionResult(false, lastCollaborationError);
        }
        String prompt = "Please revise your previous answer to address this feedback:\n" + redoNote;
        String reply = AskChatTool.askQualified(system, watchdogRef, myChatName(), workerChatId, prompt,
                WORKER_WAIT_TIMEOUT_SECONDS);
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

    /** Resolves this tab's {@code "worker"} collaborator via {@link CollaborationGraph}, or {@code null}. */
    private String resolveWorker() {
        if (collaborationGraphRef == null) return null;
        try {
            return collaborationGraphRef.ask(g -> g.getCollaborator(myChatName(), "worker")).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.log(Level.WARNING, "resolveWorker: CollaborationGraph lookup failed", e);
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
        StringBuilder buf = new StringBuilder();
        ProviderContext ctx = new ProviderContext(apiKey, List.of(), turnNoThink, () -> {});
        ActorRef<LlmProvider> providerRef = providerRef();
        try {
            providerRef.ask(p -> {
                Consumer<ChatEvent> wrapped = event -> {
                    if ("delta".equals(event.type()) && event.content() != null) {
                        buf.append(event.content());
                        turnEmitter.accept(ChatEvent.thinking(event.content()));
                    }
                };
                p.sendPrompt(prompt, turnModel, wrapped, ctx);
                return null;
            }, system.getManagedThreadPool()).get();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Babysitter judge LLM call failed", e);
            return "REVISE: judge call failed: " + e.getMessage();
        }
        return buf.toString();
    }

    /** Dispatches one tool call to its implementation and returns the raw (untruncated) observation. */
    private String executeTool(ToolCall tc) {
        String args = tc.argumentsJson();
        return switch (tc.name()) {
            case "read" -> FileReadTool.read(fileScope, extractInput(args, "path"));
            case "write" -> FileWriteTool.write(fileScope,
                    extractInput(args, "path"), extractInput(args, "content"));
            case "calc" -> calculator().evaluate(extractInput(args, "expression"));
            case "web_search" -> WebSearchTool.searchAndFetch(extractInput(args, "query"));
            case "fetch" -> FetchTool.fetch(extractInput(args, "url"));
            case "search_docs" -> DocSearchTool.search(extractInput(args, "query"), 0);
            case "list_references" -> ReferenceLinkTool.list(extractInput(args, "id"),
                    extractInput(args, "direction"), extractInput(args, "relation"));
            case "ask_chat" -> AskChatTool.ask(system, watchdogRef, projectId, chatId,
                    extractInput(args, "chatId"), extractInput(args, "prompt"),
                    parseIntOrNull(extractInput(args, "timeoutSeconds")));
            case "set_workflow" -> SetWorkflowTool.setWorkflow(system, projectId,
                    extractInput(args, "chatId"), extractInput(args, "yaml"));
            case "run_plan" -> RunPlanTool.runPlan(system, watchdogRef, myChatName(),
                    extractInput(args, "yaml"),
                    parseIntOrNull(extractInput(args, "timeoutSeconds")));
            case "load_skill" -> LoadSkillTool.load(skillRegistryRef, extractInput(args, "name"));
            case "set_collaborator" -> SetCollaboratorTool.setCollaborator(collaborationGraphRef,
                    myChatName(), extractInput(args, "chatId"), extractInput(args, "role"),
                    extractInput(args, "collaboratorChatId"));
            default -> "error: unknown tool '" + tc.name() + "'";
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

    /** @return {@code text} parsed as an {@code Integer}, or {@code null} if blank/not a number. */
    private static Integer parseIntOrNull(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JShellCalculator calculator() {
        if (calculator == null) calculator = new JShellCalculator();
        return calculator;
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
        recordHistory("assistant", assistant);
        recordTurnIo(ioSession, ++ioTurn, "(autonomous continuation)", assistant, thinking);
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
        if (content == null || content.isBlank()) return;
        conversationHistory.addLast(new HistoryEntry(role, content));
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
            logToTab("INFO", "turn" + turnNo + "/step1/llm");
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log turn record failed", e);
        }
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

    /** Removes all entries from the conversation history. */
    public void clearHistory() {
        conversationHistory.clear();
        historySnapshot.set(List.of());
        // New conversation: end the current I/O-log session and renumber turns from 1.
        if (ioLog != null) ioLog.resetSession(myChatName());
        ioTurn = 0;
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
     * One entry in the conversation history.
     *
     * @param role    the message role (e.g. "user" or "assistant")
     * @param content the message text
     */
    public record HistoryEntry(String role, String content) {}
}
