package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;

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

    public ChatSessionIIAR(String actorName, LlmProvider provider, Optional<String> configApiKey,
                     IoLogStore ioLog, IIActorSystem system) {
        super(actorName, new ChatSession(provider, configApiKey, ioLog), system);
        chatSession().setActorSystem(system);
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

    @Override
    public ActionResult callByActionName(String actionName, String arg) {
        if (actionName.equals("sendPrompt")) {
            return sendPrompt(arg);
        } else if (actionName.equals("getResult")) {
            return getResult(arg);
        }
        // execCode / runUntilEnd / call / runWorkflow / readYaml / setCurrentState etc. are
        // handled by InterpreterIIAR itself, since ChatSession is an Interpreter.
        return super.callByActionName(actionName, arg);
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
        ActorRef<PromptQueue> promptQueue = chatSession().getPromptQueue();
        if (promptQueue == null) {
            return new ActionResult(false, "PromptQueue not wired yet");
        }
        String resultKey = UUID.randomUUID().toString();
        chatSession().registerPendingResultKey(resultKey);
        promptQueue.tell(q -> q.enqueue(
                prompt, model, "queue",
                (ChatEvent event) -> {}, self(), "agent:workflow", resultKey,
                new CompletableFuture<Void>()));
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
