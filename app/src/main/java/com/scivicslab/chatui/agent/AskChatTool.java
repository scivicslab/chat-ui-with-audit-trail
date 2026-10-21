package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.CallWatchdog;
import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.PromptQueue;
import com.scivicslab.chatui.core.actor.SseConnection;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sending a prompt to another ConversationSquad's agent loop. A model's {@code ask_chat} tool call
 * ({@link #send}) does not wait: the reply comes back as a new message in the caller's queue
 * ({@code AskChatWithoutWaiting_261022_oo01}). Workflow steps that need the reply before their next
 * step ({@link #askQualified}) wait for it (graph-engineering scenarios — see
 * {@code AskChatToolAndWatchdog_260827_oo01}).
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
     * The {@code ask_chat} tool as a model calls it: puts the prompt in the target conversation's
     * queue and returns at once, without waiting for the target's turn. When that turn ends, its
     * reply and the line saying how it ended are put in the calling conversation's own queue as a new
     * message, sent as {@code agent:reply:<target>} ({@code AskChatWithoutWaiting_261022_oo01}).
     * A turn that leaves no reply is reported the same way, with what went wrong.
     *
     * <p>Nobody waits, so two conversations asking each other cannot block each other, and the
     * {@link CallWatchdog} is not consulted here.</p>
     *
     * @param system      the actor system both conversations live in
     * @param myProjectId the calling conversation's project id
     * @param myChatId    the calling conversation's own id within that project
     * @param target      the target as written by the caller — a bare id ({@code "02"}) for this
     *                    project, or a qualified one ({@code "project2/02"}) to cross into another
     * @param prompt      the instruction to send
     * @return a line saying the prompt was sent, or an {@code error: ...} string
     */
    public static String send(IIActorSystem system, String myProjectId, String myChatId,
                              String target, String prompt) {
        if (target == null || target.isBlank()) return "error: chatId is required";
        if (prompt == null || prompt.isBlank()) return "error: prompt is required";

        String myName = ChatUiActorSystem.chatActorName(myProjectId, myChatId);
        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        if (targetName.equals(myName)) return "error: cannot ask_chat yourself";
        if (!targetName.startsWith(myProjectId + "/")) {
            LOG.info("ask_chat crosses projects: " + myName + " -> " + targetName);
        }
        IIActorRef<?> targetActor = system.getIIActor(targetName + ".chat");
        if (!(targetActor instanceof ChatSessionIIAR targetChat)) {
            return "error: chat not found: " + target;
        }
        ActorRef<PromptQueue> targetQueue = system.getActor(targetName + ".queue");
        if (targetQueue == null) return "error: chat not found: " + target;
        IIActorRef<?> myActor = system.getIIActor(myName + ".chat");
        if (!(myActor instanceof ChatSessionIIAR myChat)) {
            return "error: the calling conversation " + myName + " is not registered";
        }
        ActorRef<PromptQueue> myQueue = system.getActor(myName + ".queue");
        if (myQueue == null) return "error: the calling conversation " + myName + " has no queue";

        String resultKey = UUID.randomUUID().toString();
        CompletableFuture<Void> done = new CompletableFuture<>();
        // done completes inside the target's actor; reading its result from that same thread would
        // wait on the actor that is running it, so the reply is fetched and delivered elsewhere.
        done.whenCompleteAsync((ignored, failure) ->
                deliverReply(system, myName, myChat, myQueue, targetName, targetChat, resultKey, failure));
        Consumer<ChatEvent> targetWatchers = watchersOf(system, targetName);
        targetQueue.tell(q -> q.enqueue(prompt, null, "queue", targetWatchers,
                targetChat.asChatSessionRef(), ChatSession.ASK_CHAT_SOURCE_PREFIX + myName, resultKey, done));
        return "sent to " + targetName + "; its reply will arrive in this conversation as a new message "
                + "when its turn ends";
    }

    /** Puts the target turn's reply, or the reason there is none, in the calling conversation's queue. */
    private static void deliverReply(IIActorSystem system, String myName, ChatSessionIIAR myChat,
                                     ActorRef<PromptQueue> myQueue, String targetName,
                                     ChatSessionIIAR targetChat, String resultKey, Throwable failure) {
        String message;
        if (failure != null) {
            message = noReply(targetName, failure);
        } else {
            try {
                String reply = targetChat.ask(interp -> ((ChatSession) interp).getCompletedResult(resultKey))
                        .get(30, TimeUnit.SECONDS);
                String ending = targetChat.<String>ask(interp -> ((ChatSession) interp).getCompletedEnding(resultKey))
                        .get(30, TimeUnit.SECONDS);
                message = reply == null
                        ? "[ask_chat to " + targetName + " ended without a reply: its turn left no result]"
                        : "[reply from " + targetName + " to your ask_chat]\n" + reply
                          + (ending == null ? "" : "\n\n" + ending);
            } catch (Exception e) {
                message = noReply(targetName, e);
            }
        }
        String text = message;
        Consumer<ChatEvent> myWatchers = watchersOf(system, myName);
        myQueue.tell(q -> q.enqueue(text, null, "queue", myWatchers, myChat.asChatSessionRef(),
                ChatSession.ASK_CHAT_REPLY_SOURCE_PREFIX + targetName, null, new CompletableFuture<>()));
    }

    private static String noReply(String targetName, Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        LOG.log(Level.WARNING, "ask_chat to " + targetName + " ended without a reply", cause);
        return "[ask_chat to " + targetName + " ended without a reply: " + cause.getClass().getSimpleName()
                + ": " + cause.getMessage() + "]";
    }

    /** What a conversation says goes to whoever is watching it; nothing when no SSE actor is registered (unit tests). */
    private static Consumer<ChatEvent> watchersOf(IIActorSystem system, String chatName) {
        ActorRef<SseConnection> sse = system.getActor(chatName + ".sse");
        return sse == null ? event -> { } : event -> sse.tell(c -> c.emit(event));
    }

    /**
     * Same call, but with both conversations already named in full ({@code project1/chat-03}) —
     * for callers that hold qualified names rather than what a user typed, such as the babysitter
     * phases resolving their {@code "worker"} through {@link com.scivicslab.chatui.core.actor.CollaborationGraph}
     * (which stores qualified names). Resolving such a name a second time would mangle it.
     *
     * @param system     the actor system used to resolve the target's actors
     * @param watchdog   the shared {@link CallWatchdog}
     * @param myName     the calling conversation's qualified name
     * @param targetName the target conversation's qualified name
     * @param prompt     the instruction to send
     * @param timeoutSeconds how long to wait, or {@code null}/non-positive for the default
     * @return the target's reply text, or an {@code error: ...} string
     */
    public static String askQualified(IIActorSystem system, ActorRef<CallWatchdog> watchdog,
                                       String myName, String targetName, String prompt,
                                       Integer timeoutSeconds) {
        return askWithEnding(system, watchdog, myName, targetName, prompt, timeoutSeconds).reply();
    }

    /**
     * A reply, and the line saying how its turn ended ({@link ChatInspection#endingLine}).
     *
     * @param reply  the target's reply text, or an {@code error: ...} string
     * @param ending the ending line, or {@code null} when there was no turn to describe (an error,
     *               or a turn that left none)
     */
    record Outcome(String reply, String ending) {
        static Outcome of(String reply) { return new Outcome(reply, null); }
    }

    /** {@link #askQualified}, with the target turn's ending line alongside the reply. */
    static Outcome askWithEnding(IIActorSystem system, ActorRef<CallWatchdog> watchdog,
                                  String myName, String targetName, String prompt,
                                  Integer timeoutSeconds) {
        int waitTimeoutSeconds = (timeoutSeconds != null && timeoutSeconds > 0)
                ? timeoutSeconds : DEFAULT_WAIT_TIMEOUT_SECONDS;
        String target = targetName;
        if (targetName.equals(myName)) return Outcome.of("error: cannot ask_chat yourself");

        IIActorRef<?> targetIIActor = system.getIIActor(targetName + ".chat");
        if (!(targetIIActor instanceof ChatSessionIIAR targetChatSessionIIAR)) {
            return Outcome.of("error: chat not found: " + target);
        }
        ActorRef<PromptQueue> targetQueueRef = system.getActor(targetName + ".queue");
        if (targetQueueRef == null) {
            return Outcome.of("error: chat not found: " + target);
        }
        // What the asked conversation says goes to whoever is watching that conversation, exactly
        // as it would if a person had typed the prompt. Nothing was passed here before, so a turn
        // a workflow asked for ran with the screen showing nothing until it was over
        // (PromptByValueHidesTheWork_260915_oo01). Absent when no SSE actor is registered, which
        // is the case in the unit tests.
        ActorRef<SseConnection> targetSseRef = system.getActor(targetName + ".sse");
        Consumer<ChatEvent> toWatchers = targetSseRef == null
                ? event -> { }
                : event -> targetSseRef.tell(sse -> sse.emit(event));

        boolean allowed;
        try {
            allowed = watchdog.ask(w -> w.beginWait(myName, targetName)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ask_chat: watchdog check failed", e);
            return Outcome.of("error: watchdog check failed: " + e.getMessage());
        }
        if (!allowed) {
            return Outcome.of("error: refused — asking " + target + " would create a circular wait");
        }

        try {
            CompletableFuture<Void> done = new CompletableFuture<>();
            String resultKey = UUID.randomUUID().toString();
            targetQueueRef.tell(q -> q.enqueue(prompt, null, "queue",
                    toWatchers,
                    targetChatSessionIIAR.asChatSessionRef(), ChatSession.ASK_CHAT_SOURCE_PREFIX + myName, resultKey,
                    done));
            try {
                done.get(waitTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                return Outcome.of("error: chat " + target + " did not respond within " + waitTimeoutSeconds + "s");
            }
            String reply = targetChatSessionIIAR
                    .ask(interp -> ((ChatSession) interp).getCompletedResult(resultKey))
                    .get(5, TimeUnit.SECONDS);
            if (reply == null) return Outcome.of("error: no result from chat " + target);
            String ending = targetChatSessionIIAR
                    .<String>ask(interp -> ((ChatSession) interp).getCompletedEnding(resultKey))
                    .get(5, TimeUnit.SECONDS);
            return new Outcome(reply, ending);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ask_chat: call to " + target + " failed", e);
            return Outcome.of("error: ask_chat failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            watchdog.tell(w -> w.endWait(myName));
        }
    }
}
