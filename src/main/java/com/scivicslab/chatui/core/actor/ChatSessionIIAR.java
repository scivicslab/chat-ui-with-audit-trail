package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.rest.ChatEvent;
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

    @Override
    public ActionResult callByActionName(String actionName, String arg) {
        if (actionName.equals("sendPrompt")) {
            return sendPrompt(arg);
        } else if (actionName.equals("getResult")) {
            return getResult(arg);
        } else if (actionName.equals("stepExpectingAction")) {
            // Dispatched by chat-session-agent-loop.yaml ("actor: this") from inside
            // Interpreter.execCode(), itself only ever invoked as a plain runUntilEnd() call
            // from within an existing tell() closure — so this runs on ChatSession's own
            // actor thread, not on IIActorSystem's ManagedThreadPool.
            return chatSession().stepExpectingAction();
        } else if (actionName.equals("runTool")) {
            // The workflow's argument is how much of each observation the model may see
            // (TurnResourceLimits_260830_oo01); absent, ChatSession falls back to its default.
            return chatSession().runTool(firstArgument(arg));
        } else if (actionName.equals("stepLimitReached")) {
            // The step-limit transition's guard. The number is the workflow's, not Java's.
            return chatSession().stepLimitReached(firstArgument(arg));
        } else if (actionName.equals("finish")) {
            return chatSession().finish();
        } else if (actionName.equals("hasMoreConstructedPrompts")) {
            // Dispatched by chat-session-agent-loop.yaml's "think-continue" transition.
            return chatSession().hasMoreConstructedPrompts();
        } else if (actionName.equals("buildDefaultPrompt")) {
            // Dispatched by prompt-construction-default.yaml ("actor: ..") from a sub-workflow
            // Interpreter.call() creates — see ChatSessionPorting_260823_oo01 2-b-i.
            chatSession().buildDefaultPrompt();
            return new ActionResult(true, "");
        } else if (actionName.equals("currentPromptText")) {
            // Dispatched by a prompt-construction sub-workflow that wraps this turn's text
            // (DocRetrievalAgentLoop_260830_oo01); the text arrives in the workflow's ${result}.
            return chatSession().currentPromptText();
        } else if (actionName.equals("appendConstructedPrompt")) {
            // Generic primitive any prompt-construction sub-workflow (default or swapped-in) can
            // call, possibly more than once, to hand back the prompt(s) it built.
            String prompt = new org.json.JSONArray(arg).getString(0);
            chatSession().appendConstructedPrompt(prompt);
            return new ActionResult(true, "");
        } else if (actionName.equals("requestFromWorker")) {
            // Dispatched by the babysitter workflows ("actor: this"). What differs between phases
            // is the YAML `arguments`, not the method name (GenericBabysitterPhases_260829_oo01);
            // Interpreter.convertArgumentsToJson delivers them as a JSON array.
            return chatSession().requestFromWorker(firstArgument(arg));
        } else if (actionName.equals("judgeResult")) {
            return chatSession().judgeResult(firstArgument(arg));
        } else if (actionName.equals("retryLimitReached")) {
            return chatSession().retryLimitReached(firstArgument(arg));
        } else if (actionName.equals("judgeNeedsRedo")) {
            return chatSession().judgeNeedsRedo();
        } else if (actionName.equals("requestRedo")) {
            return chatSession().requestRedo();
        } else if (actionName.equals("reportCollaborationFailure")) {
            return chatSession().reportCollaborationFailure();
        }
        // execCode / runUntilEnd / call / runWorkflow / readYaml / setCurrentState etc. are
        // handled by InterpreterIIAR itself, since ChatSession is an Interpreter.
        return super.callByActionName(actionName, arg);
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
