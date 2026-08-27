package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.CallWatchdog;
import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.PromptQueue;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code ask_chat} tool: sends a prompt to another conversation tab's agent loop and waits for
 * its reply, so one tab's agent can direct or review another's work (graph-engineering scenarios —
 * see {@code AskChatToolAndWatchdog_260827_oo01}).
 *
 * <p>Waits on {@link ChatSession}'s own turn-completion signal (the {@code CompletableFuture<Void>
 * done} that {@code PromptQueue.enqueue}/{@code ChatSession.start} already thread through and
 * complete reliably in {@code onPromptComplete}) rather than polling — a real {@code Future}, timed
 * out the ordinary way. Consults {@link CallWatchdog} before waiting, so a call that would create a
 * circular wait is refused immediately instead of blocking until it times out.</p>
 */
public final class AskChatTool {

    private AskChatTool() {}

    private static final Logger LOG = Logger.getLogger(AskChatTool.class.getName());

    /** Default wait, used when the caller doesn't specify one — generous, since the target may itself call tools. */
    private static final int DEFAULT_WAIT_TIMEOUT_SECONDS = 60;

    /**
     * @param system      this tab's actor system, used to resolve the target tab's actors
     * @param watchdog    the shared {@link CallWatchdog}
     * @param myTabId     the calling tab's own id (the caller of {@code ask_chat})
     * @param targetTabId the tab id to send {@code prompt} to
     * @param prompt      the instruction to send
     * @param timeoutSeconds how long to wait for the target's reply, or {@code null}/non-positive to
     *                       use {@link #DEFAULT_WAIT_TIMEOUT_SECONDS} — callers directing a target
     *                       through a multi-hop chain (e.g. it will itself call {@code ask_chat})
     *                       should pass a longer budget ({@code AskChatNestedTimeout_260828_oo01})
     * @return the target tab's reply text, or an {@code error: ...} string
     */
    public static String ask(IIActorSystem system, ActorRef<CallWatchdog> watchdog,
                              String myTabId, String targetTabId, String prompt, Integer timeoutSeconds) {
        int waitTimeoutSeconds = (timeoutSeconds != null && timeoutSeconds > 0)
                ? timeoutSeconds : DEFAULT_WAIT_TIMEOUT_SECONDS;
        if (targetTabId == null || targetTabId.isBlank()) return "error: chatId is required";
        if (prompt == null || prompt.isBlank()) return "error: prompt is required";
        if (targetTabId.equals(myTabId)) return "error: cannot ask_chat yourself";

        IIActorRef<?> targetIIActor = system.getIIActor("chat-" + targetTabId + ".chat");
        if (!(targetIIActor instanceof ChatSessionIIAR targetChatSessionIIAR)) {
            return "error: chat not found: " + targetTabId;
        }
        ActorRef<PromptQueue> targetQueueRef = system.getActor("chat-" + targetTabId + ".queue");
        if (targetQueueRef == null) {
            return "error: chat not found: " + targetTabId;
        }

        boolean allowed;
        try {
            allowed = watchdog.ask(w -> w.beginWait(myTabId, targetTabId)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ask_chat: watchdog check failed", e);
            return "error: watchdog check failed: " + e.getMessage();
        }
        if (!allowed) {
            return "error: refused — asking " + targetTabId + " would create a circular wait";
        }

        try {
            CompletableFuture<Void> done = new CompletableFuture<>();
            String resultKey = UUID.randomUUID().toString();
            targetQueueRef.tell(q -> q.enqueue(prompt, null, "queue",
                    (ChatEvent event) -> {},
                    targetChatSessionIIAR.asChatSessionRef(), "agent:ask_chat:" + myTabId, resultKey,
                    done));
            try {
                done.get(waitTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                return "error: chat " + targetTabId + " did not respond within " + waitTimeoutSeconds + "s";
            }
            String reply = targetChatSessionIIAR
                    .ask(interp -> ((ChatSession) interp).getCompletedResult(resultKey))
                    .get(5, TimeUnit.SECONDS);
            return reply != null ? reply : "error: no result from chat " + targetTabId;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ask_chat: call to " + targetTabId + " failed", e);
            return "error: ask_chat failed: " + e.getMessage();
        } finally {
            watchdog.tell(w -> w.endWait(myTabId));
        }
    }
}
