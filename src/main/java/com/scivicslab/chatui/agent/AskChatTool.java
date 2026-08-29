package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.CallWatchdog;
import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
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
     * @param myProjectId the calling conversation's project id
     * @param myChatId    the calling conversation's own id within that project
     * @param target      the target as written by the caller — a bare id ({@code "02"}) for this
     *                    project, or a qualified one ({@code "project2/02"}) to cross into another
     * @param prompt      the instruction to send
     * @param timeoutSeconds how long to wait for the target's reply, or {@code null}/non-positive to
     *                       use {@link #DEFAULT_WAIT_TIMEOUT_SECONDS} — callers directing a target
     *                       through a multi-hop chain (e.g. it will itself call {@code ask_chat})
     *                       should pass a longer budget ({@code AskChatNestedTimeout_260828_oo01})
     * @return the target tab's reply text, or an {@code error: ...} string
     */
    public static String ask(IIActorSystem system, ActorRef<CallWatchdog> watchdog,
                              String myProjectId, String myChatId, String target,
                              String prompt, Integer timeoutSeconds) {
        int waitTimeoutSeconds = (timeoutSeconds != null && timeoutSeconds > 0)
                ? timeoutSeconds : DEFAULT_WAIT_TIMEOUT_SECONDS;
        if (target == null || target.isBlank()) return "error: chatId is required";
        if (prompt == null || prompt.isBlank()) return "error: prompt is required";

        String myName = ChatUiActorSystem.chatActorName(myProjectId, myChatId);
        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        if (targetName.equals(myName)) return "error: cannot ask_chat yourself";
        if (!targetName.startsWith(myProjectId + "/")) {
            // Allowed, but recorded: ask_chat only puts a message in the target's mailbox, so it
            // needs no gateway — crossing is still worth noting (ProjectNamespacePrefix_260829_oo01).
            LOG.info("ask_chat crosses projects: " + myName + " -> " + targetName);
        }

        IIActorRef<?> targetIIActor = system.getIIActor(targetName + ".chat");
        if (!(targetIIActor instanceof ChatSessionIIAR targetChatSessionIIAR)) {
            return "error: chat not found: " + target;
        }
        ActorRef<PromptQueue> targetQueueRef = system.getActor(targetName + ".queue");
        if (targetQueueRef == null) {
            return "error: chat not found: " + target;
        }

        boolean allowed;
        try {
            allowed = watchdog.ask(w -> w.beginWait(myName, targetName)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ask_chat: watchdog check failed", e);
            return "error: watchdog check failed: " + e.getMessage();
        }
        if (!allowed) {
            return "error: refused — asking " + target + " would create a circular wait";
        }

        try {
            CompletableFuture<Void> done = new CompletableFuture<>();
            String resultKey = UUID.randomUUID().toString();
            targetQueueRef.tell(q -> q.enqueue(prompt, null, "queue",
                    (ChatEvent event) -> {},
                    targetChatSessionIIAR.asChatSessionRef(), "agent:ask_chat:" + myName, resultKey,
                    done));
            try {
                done.get(waitTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                return "error: chat " + target + " did not respond within " + waitTimeoutSeconds + "s";
            }
            String reply = targetChatSessionIIAR
                    .ask(interp -> ((ChatSession) interp).getCompletedResult(resultKey))
                    .get(5, TimeUnit.SECONDS);
            return reply != null ? reply : "error: no result from chat " + target;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ask_chat: call to " + target + " failed", e);
            return "error: ask_chat failed: " + e.getMessage();
        } finally {
            watchdog.tell(w -> w.endWait(myName));
        }
    }
}
