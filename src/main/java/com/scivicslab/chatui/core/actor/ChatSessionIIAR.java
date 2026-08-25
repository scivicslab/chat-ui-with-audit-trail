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

    private static final String AGENT_LOOP_YAML = "/workflows/chat-session-agent-loop.yaml";

    public ChatSessionIIAR(String actorName, LlmProvider provider, Optional<String> configApiKey,
                     IoLogStore ioLog, IIActorSystem system) {
        super(actorName, new ChatSession(provider, configApiKey, ioLog), system);
        chatSession().setActorSystem(system);
        // InterpreterIIAR's constructor does not call this itself — without it, "actor: this" in
        // chat-session-agent-loop.yaml resolves through Interpreter.action()'s selfActorRef==null
        // fallback (system.getIIActor("this")), which finds nothing ("Actor not found: this").
        chatSession().setSelfActorRef(this);
        try (InputStream yaml = getClass().getResourceAsStream(AGENT_LOOP_YAML)) {
            if (yaml == null) {
                throw new IllegalStateException("Agent-loop workflow not found on classpath: " + AGENT_LOOP_YAML);
            }
            chatSession().readYaml(yaml);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load agent-loop workflow: " + AGENT_LOOP_YAML, e);
        }
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
            return chatSession().runTool();
        } else if (actionName.equals("finish")) {
            return chatSession().finish();
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
