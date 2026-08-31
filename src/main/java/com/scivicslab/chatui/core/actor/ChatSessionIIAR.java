package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code IIActorRef} adapter that lets a Turing workflow call a conversation tab's
 * {@link ChatSession} by name — see {@code ChatSessionIIAR_260810_oo01}.
 *
 * <p>Extends {@link InterpreterIIAR} (rather than {@code IIActorRef<ChatSession>} directly)
 * because {@link ChatSession} itself extends {@code Interpreter}
 * ({@code ChatSessionAgentLoop_260823_oo01}): this lets an outer workflow drive a session's
 * turn with the same vocabulary (e.g. {@code runUntilEnd}) it would use for any other
 * sub-workflow, inherited for free from {@link InterpreterIIAR}.</p>
 */
public class ChatSessionIIAR extends InterpreterIIAR {

    /**
     * This tab's agent-loop workflow, by classpath-relative file name under {@code /workflows/} —
     * a per-instance setting (mirrors {@link ChatSession#promptWorkflowFile}), not a fixed value.
     * Different reusable workflow files can exist (today only {@code chat-session-agent-loop.yaml},
     * a plain tool-call loop; others — e.g. a paper-search loop — can be added later), and each tab
     * picks which one it runs; multiple tabs may point at the same file. All tabs default to the
     * same file today since it's the only one that exists, not because tabs are forced to share it.
     */
    private String agentLoopWorkflowFile = "chat-session-agent-loop.yaml";

    public ChatSessionIIAR(String actorName, LlmProvider provider, Optional<String> configApiKey,
                     IoLogStore ioLog, IIActorSystem system) {
        super(actorName, new ChatSession(provider, configApiKey, ioLog), system);
        chatSession().setActorSystem(system);
        // InterpreterIIAR's constructor does not call this itself — without it, "actor: this" in
        // chat-session-agent-loop.yaml resolves through Interpreter.action()'s selfActorRef==null
        // fallback (system.getIIActor("this")), which finds nothing ("Actor not found: this").
        chatSession().setSelfActorRef(this);
        loadAgentLoopWorkflow();
    }

    private void loadAgentLoopWorkflow() {
        String resource = "/workflows/" + agentLoopWorkflowFile;
        try (InputStream yaml = getClass().getResourceAsStream(resource)) {
            if (yaml == null) {
                throw new IllegalStateException("Agent-loop workflow not found on classpath: " + resource);
            }
            chatSession().readYaml(yaml);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load agent-loop workflow: " + resource, e);
        }
    }

    /** @return this tab's agent-loop workflow file name (classpath-relative, under {@code /workflows/}) */
    public String getAgentLoopWorkflowFile() { return agentLoopWorkflowFile; }

    /**
     * Replaces this conversation's agent loop with another workflow, the same way the
     * {@code set_workflow} tool does — reset first, then read
     * ({@code WorkflowReloadReset_260828_oo01}). Used to select a variant from outside the
     * conversation ({@code DocRetrievalAgentLoop_260830_oo01}).
     *
     * @param file classpath file name under {@code /workflows/}
     * @return {@code null} on success, or an {@code error: ...} string
     */
    public String setAgentLoopWorkflowFile(String file) {
        String previous = agentLoopWorkflowFile;
        agentLoopWorkflowFile = file;
        try {
            chatSession().reset();
            loadAgentLoopWorkflow();
            return null;
        } catch (RuntimeException e) {
            agentLoopWorkflowFile = previous;
            return "error: " + e.getMessage();
        }
    }

    /**
     * Reads this tab's busy flag directly, bypassing the actor's mailbox — safe even while a long
     * turn (e.g. {@code ask_chat}) is in progress, since {@code ChatSession.busy} is {@code volatile}
     * and only ever written from this actor's own thread ({@code BusyStateReadableSnapshot_260828_oo01}).
     *
     * @return {@code true} if this tab is currently processing a turn
     */
    public boolean isBusyDirect() { return chatSession().isBusy(); }

    /**
     * Reads this tab's conversation-history snapshot directly, bypassing the actor's mailbox — same
     * rationale as {@link #isBusyDirect()}.
     *
     * @return an immutable snapshot of the conversation history as of the last recorded turn
     */
    public java.util.List<ChatSession.HistoryEntry> getHistorySnapshotDirect() {
        return chatSession().historySnapshot();
    }

    private ChatSession chatSession() { return (ChatSession) object; }

    /**
     * {@code this}'s static type is {@code ActorRef<Interpreter>} (inherited from
     * {@link InterpreterIIAR}), not {@code ActorRef<ChatSession>} — Java generics are
     * invariant, so extending {@code InterpreterIIAR} instead of {@code IIActorRef<ChatSession>}
     * loses that direct assignability even though the wrapped object really is a
     * {@code ChatSession}. This cast is safe: at runtime {@code object} is the
     * {@code ChatSession} passed to the constructor above.
     */
    @SuppressWarnings("unchecked")
    private ActorRef<ChatSession> self() { return (ActorRef<ChatSession>) (ActorRef<?>) this; }

    /**
     * Public form of {@link #self()}, for callers outside this class that need to pass this
     * session's {@code ActorRef<ChatSession>} to another actor's method (e.g. {@code ChatResource}
     * enqueuing a browser-typed prompt via {@code PromptQueue.enqueue(..., chatSessionRef, ...)}).
     *
     * @return this actor reference, viewed as {@code ActorRef<ChatSession>}
     */
    public ActorRef<ChatSession> asChatSessionRef() { return self(); }

    // ── Actions the workflow YAML names ──────────────────────────────────────
    // Annotated rather than dispatched by an overridden callByActionName: IIActorRef's own
    // javadoc names that override as the pattern not to use, and it costs a registration step
    // that is easy to forget — a method added without its entry fails as "action not found",
    // which from outside is indistinguishable from a transition whose condition simply did not
    // hold. IIActorRef's dispatcher looks for @Action on this adapter, and InterpreterIIAR
    // passes anything it does not handle itself (execCode / runUntilEnd / call / readYaml /
    // setCurrentState ...) down to it.

    /**
     * @param arg the prompt, as the workflow wrote it
     * @return the result key to poll {@link #getResult} with
     */
    @Action("sendPrompt")
    public ActionResult sendPromptAction(String arg) {
        return sendPrompt(arg);
    }

    /**
     * @param arg the result key returned by {@code sendPrompt}
     * @return the answer once the turn has finished
     */
    @Action("getResult")
    public ActionResult getResultAction(String arg) {
        return getResult(arg);
    }

    /**
     * One LLM call. Runs on ChatSession's own actor thread: the workflow reaches this from
     * inside {@code Interpreter.execCode()}, itself only ever invoked as a plain
     * {@code runUntilEnd()} call from within an existing {@code tell()} closure.
     *
     * @param arg unused
     * @return success when the reply asked for a tool
     */
    @Action("stepExpectingAction")
    public ActionResult stepExpectingActionAction(String arg) {
        return chatSession().stepExpectingAction();
    }

    /**
     * @param arg how much of each observation the model may see; absent, the session's default
     *            applies ({@code TurnResourceLimits_260830_oo01})
     * @return always successful
     */
    @Action("runTool")
    public ActionResult runToolAction(String arg) {
        return chatSession().runTool(firstArgument(arg));
    }

    /**
     * The step-limit transition's guard. The number is the workflow's, not Java's.
     *
     * @param arg the step limit
     * @return success once the turn has used its steps
     */
    @Action("stepLimitReached")
    public ActionResult stepLimitReachedAction(String arg) {
        return chatSession().stepLimitReached(firstArgument(arg));
    }

    /**
     * @param arg unused
     * @return always successful
     */
    @Action("finish")
    public ActionResult finishAction(String arg) {
        return chatSession().finish();
    }

    /**
     * The "think-continue" transition's guard.
     *
     * @param arg unused
     * @return success while constructed prompts remain queued
     */
    @Action("hasMoreConstructedPrompts")
    public ActionResult hasMoreConstructedPromptsAction(String arg) {
        return chatSession().hasMoreConstructedPrompts();
    }

    /**
     * Dispatched by {@code prompt-construction-default.yaml} ({@code actor: ..}) from the
     * sub-workflow {@code Interpreter.call()} creates.
     *
     * @param arg unused
     * @return always successful
     */
    @Action("buildDefaultPrompt")
    public ActionResult buildDefaultPromptAction(String arg) {
        chatSession().buildDefaultPrompt();
        return new ActionResult(true, "");
    }

    /**
     * The turn's own text, for a sub-workflow that wraps it in something else
     * ({@code DocRetrievalAgentLoop_260830_oo01}); it arrives in the workflow's {@code ${result}}.
     *
     * @param arg unused
     * @return the text
     */
    @Action("currentPromptText")
    public ActionResult currentPromptTextAction(String arg) {
        return chatSession().currentPromptText();
    }

    /**
     * The primitive any prompt-construction sub-workflow can call, more than once if it splits
     * one turn into several prompts.
     *
     * @param arg the constructed prompt
     * @return always successful
     */
    @Action("appendConstructedPrompt")
    public ActionResult appendConstructedPromptAction(String arg) {
        chatSession().appendConstructedPrompt(new org.json.JSONArray(arg).getString(0));
        return new ActionResult(true, "");
    }

    /**
     * Entry point of a babysitter phase. What differs between phases is this argument, not the
     * method name ({@code GenericBabysitterPhases_260829_oo01}).
     *
     * @param arg what to ask the worker to do
     * @return success when the worker replied
     */
    @Action("requestFromWorker")
    public ActionResult requestFromWorkerAction(String arg) {
        return chatSession().requestFromWorker(firstArgument(arg));
    }

    /**
     * @param arg the criteria to judge the worker's reply against
     * @return success when the reply meets them
     */
    @Action("judgeResult")
    public ActionResult judgeResultAction(String arg) {
        return chatSession().judgeResult(firstArgument(arg));
    }

    /**
     * @param arg this phase's redo budget
     * @return success once the budget is spent
     */
    @Action("retryLimitReached")
    public ActionResult retryLimitReachedAction(String arg) {
        return chatSession().retryLimitReached(firstArgument(arg));
    }

    /**
     * The judging state's catch-all.
     *
     * @param arg unused
     * @return always successful
     */
    @Action("judgeNeedsRedo")
    public ActionResult judgeNeedsRedoAction(String arg) {
        return chatSession().judgeNeedsRedo();
    }

    /**
     * @param arg unused
     * @return success when the worker replied to the redo request
     */
    @Action("requestRedo")
    public ActionResult requestRedoAction(String arg) {
        return chatSession().requestRedo();
    }

    // ── 型3: 文書検索の状態機械 (DocRetrievalAgentLoop_260830_oo01) ──

    /**
     * @param arg unused
     * @return success when the search returned candidates
     */
    @Action("searchDocs")
    public ActionResult searchDocsAction(String arg) {
        return chatSession().searchDocs(arg);
    }

    /**
     * @param arg unused
     * @return success when the candidate list looks able to answer the question
     */
    @Action("judgeHitsSufficient")
    public ActionResult judgeHitsSufficientAction(String arg) {
        return chatSession().judgeHitsSufficient(arg);
    }

    /**
     * @param arg how many searches this turn may run
     * @return success once that many have been run
     */
    @Action("searchLimitReached")
    public ActionResult searchLimitReachedAction(String arg) {
        return chatSession().searchLimitReached(firstArgument(arg));
    }

    /**
     * @param arg unused
     * @return always successful
     */
    @Action("judgeHitsNeedRefinement")
    public ActionResult judgeHitsNeedRefinementAction(String arg) {
        return chatSession().judgeHitsNeedRefinement(arg);
    }

    /**
     * @param arg unused
     * @return success when the new search returned candidates
     */
    @Action("refineQueryAndSearch")
    public ActionResult refineQueryAndSearchAction(String arg) {
        return chatSession().refineQueryAndSearch(arg);
    }

    /**
     * @param arg how many documents to open at most
     * @return success when at least one was opened
     */
    @Action("readSources")
    public ActionResult readSourcesAction(String arg) {
        return chatSession().readSources(firstArgument(arg));
    }

    /**
     * @param arg unused
     * @return always successful
     */
    @Action("reportRetrievalFailure")
    public ActionResult reportRetrievalFailureAction(String arg) {
        return chatSession().reportRetrievalFailure(arg);
    }

    /**
     * @param arg unused
     * @return always successful
     */
    @Action("answerFromSources")
    public ActionResult answerFromSourcesAction(String arg) {
        return chatSession().answerFromSources(arg);
    }

    /**
     * Ends the turn with an explanation instead of leaving it stuck busy.
     *
     * @param arg unused
     * @return always successful
     */
    @Action("reportCollaborationFailure")
    public ActionResult reportCollaborationFailureAction(String arg) {
        return chatSession().reportCollaborationFailure();
    }


    /**
     * The first element of an action's {@code arguments}, or {@code ""} if it has none — the shape
     * {@code Interpreter.convertArgumentsToJson} produces for a single string argument.
     *
     * @param arg the raw arguments JSON handed to {@link #callByActionName}
     * @return the first argument, or {@code ""}
     */
    private static String firstArgument(String arg) {
        if (arg == null || arg.isBlank()) return "";
        try {
            org.json.JSONArray a = new org.json.JSONArray(arg);
            return a.isEmpty() ? "" : a.getString(0);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Accepts a prompt without blocking: registers a result key, hands the prompt to
     * {@code PromptQueue} (so a human typing in the browser at the same time is not clobbered —
     * see {@code ChatSessionIIAR_260810_oo01} "なぜ PromptQueue を経由する必要があるか"), and
     * returns the key immediately. Poll {@link #getResult} for the outcome.
     */
    private ActionResult sendPrompt(String arg) {
        org.json.JSONObject args = new org.json.JSONObject(arg == null ? "{}" : arg);
        String prompt = args.getString("prompt");
        String model = args.optString("model", null);
        boolean noThink = args.optBoolean("noThink", false);
        ActorRef<PromptQueue> promptQueue = chatSession().getPromptQueue();
        if (promptQueue == null) {
            return new ActionResult(false, "PromptQueue not wired yet");
        }
        String resultKey = UUID.randomUUID().toString();
        chatSession().registerPendingResultKey(resultKey);
        promptQueue.tell(q -> q.enqueue(
                prompt, model, "queue",
                (ChatEvent event) -> {}, self(), "agent:workflow", resultKey,
                new CompletableFuture<Void>(), noThink));
        return new ActionResult(true, resultKey);
    }

    private ActionResult getResult(String arg) {
        org.json.JSONObject args = new org.json.JSONObject(arg == null ? "{}" : arg);
        String resultKey = args.getString("resultKey");
        String status = chatSession().getResultStatus(resultKey);
        if ("completed".equals(status)) {
            return new ActionResult(true, chatSession().getCompletedResult(resultKey));
        }
        return new ActionResult(false, status);
    }
}
