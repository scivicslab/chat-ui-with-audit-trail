package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.CallWatchdog;
import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.PromptQueue;
import com.scivicslab.chatui.core.actor.SseConnection;
import com.scivicslab.chatui.core.actor.TurnReply;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

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
 * <p>Both read the reply from {@link ChatSession}'s own turn-completion signal: the
 * {@code CompletableFuture<TurnReply> done} that {@code PromptQueue.enqueue}/{@code ChatSession.start}
 * thread through, and that the conversation completes in {@code onPromptComplete} with the turn's
 * answer and ending line. Neither asks the conversation for the answer afterwards: by then it may be
 * running its next queued prompt, and the question would wait behind that whole turn.
 * {@link #askQualified} consults {@link CallWatchdog} before waiting, so a call that would create a
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
     * @param requestId   this request's id, {@code <sending turn id>:r<n>} ({@code TurnAndRequestIds_261107_oo01})
     * @return a line saying the prompt was sent and under which request id, or an {@code error: ...} string
     */
    public static String send(IIActorSystem system, String myProjectId, String myChatId,
                              String target, String prompt, String requestId) {
        return send(system, myProjectId, myChatId, target, prompt, requestId, false);
    }

    /**
     * The {@code ask_chat_urgent} tool: as {@link #send}, but the request goes into the target's
     * queue ahead of every waiting item that is not urgent, and so does its reply in this
     * conversation's queue ({@code UrgentRequestAndStatusReport_261107_oo01}).
     *
     * @return a line saying the request was sent, under which id, and how many waiting items it passed
     */
    public static String sendUrgent(IIActorSystem system, String myProjectId, String myChatId,
                                    String target, String prompt, String requestId) {
        return send(system, myProjectId, myChatId, target, prompt, requestId, true);
    }

    private static String send(IIActorSystem system, String myProjectId, String myChatId,
                               String target, String prompt, String requestId, boolean urgent) {
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
            return "error: chat not found: " + target + OtherInstances.notFoundHint(targetName);
        }
        ActorRef<PromptQueue> targetQueue = system.getActor(targetName + ".queue");
        if (targetQueue == null) return "error: chat not found: " + target + OtherInstances.notFoundHint(targetName);
        IIActorRef<?> myActor = system.getIIActor(myName + ".chat");
        if (!(myActor instanceof ChatSessionIIAR myChat)) {
            return "error: the calling conversation " + myName + " is not registered";
        }
        ActorRef<PromptQueue> myQueue = system.getActor(myName + ".queue");
        if (myQueue == null) return "error: the calling conversation " + myName + " has no queue";

        // The request id is unique within this instance, so it also serves as the key the target
        // stores its answer under.
        String resultKey = requestId;
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        // done completes inside the target's actor, carrying the answer; the delivery runs elsewhere
        // so that the target's thread is not held while the caller's queue takes the message.
        done.whenCompleteAsync((reply, failure) ->
                deliverReply(system, myName, myChat, myQueue, targetName, requestId, urgent, reply, failure));
        Consumer<ChatEvent> targetWatchers = watchersOf(system, targetName);
        String headed = "[" + (urgent ? "urgent request " : "request ") + requestId + " from " + myName + "]\n" + prompt;
        int passed;
        try {
            // Asked rather than told: the result says how many waiting items an urgent request passed.
            // The queue is its own actor, so this does not wait for the target's turn.
            passed = targetQueue.ask(q -> q.enqueue(headed, null, "queue", targetWatchers,
                    targetChat.asChatSessionRef(), ChatSession.ASK_CHAT_SOURCE_PREFIX + myName, resultKey, done,
                    false, true, java.util.List.of(), requestId, urgent)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ask_chat: could not queue request " + requestId + " for " + targetName, e);
            return "error: could not queue the request for " + targetName + ": " + e.getClass().getSimpleName();
        }
        // The sender may have meant a conversation of the same name in another instance (project numbers
        // are local to an instance), so a delivery across projects says where it went.
        String elsewhere = targetName.startsWith(myProjectId + "/") ? "" : OtherInstances.sameNameNote(targetName);
        return "sent to " + targetName + " as " + (urgent ? "urgent request " : "request ") + requestId
                + (urgent ? ", ahead of " + passed + " waiting message(s)" : "")
                + "; its reply will arrive in this conversation as a new message when its turn ends" + elsewhere;
    }

    /** Puts the target turn's reply, or the reason there is none, in the calling conversation's queue. */
    private static void deliverReply(IIActorSystem system, String myName, ChatSessionIIAR myChat,
                                     ActorRef<PromptQueue> myQueue, String targetName, String requestId,
                                     boolean urgent, TurnReply reply, Throwable failure) {
        String text = replyMessage(targetName, requestId, reply, failure);
        Consumer<ChatEvent> myWatchers = watchersOf(system, myName);
        myQueue.tell(q -> q.enqueue(text, null, "queue", myWatchers, myChat.asChatSessionRef(),
                ChatSession.ASK_CHAT_REPLY_SOURCE_PREFIX + targetName, null, new CompletableFuture<>(),
                false, true, java.util.List.of(), requestId, urgent));
    }

    /**
     * Returns the message the calling conversation receives when the target's turn ends.
     *
     * @param targetName the target conversation's qualified name
     * @param requestId  the id of the request being answered
     * @param reply      what {@code done} was completed with, or {@code null} if it failed
     * @param failure    what {@code done} failed with, or {@code null} if it completed
     * @return {@code [reply to request <id>, from <turn id>]} followed by the answer and the ending line,
     *         or a {@code [request <id> to <target> ended without a reply: ...]} line saying why there is none
     */
    static String replyMessage(String targetName, String requestId, TurnReply reply, Throwable failure) {
        if (failure != null) return noReply(targetName, requestId, failure);
        if (reply == null || reply.reply() == null) {
            String why = reply != null && reply.ending() != null ? reply.ending() : "its turn left no result";
            return "[request " + requestId + " to " + targetName + " ended without a reply: " + why + "]";
        }
        String from = reply.turnId() == null ? targetName : reply.turnId();
        return "[reply to request " + requestId + ", from " + from + "]\n" + reply.reply()
                + (reply.ending() == null ? "" : "\n\n" + reply.ending());
    }

    private static String noReply(String targetName, String requestId, Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        LOG.log(Level.WARNING, "request " + requestId + " to " + targetName + " ended without a reply", cause);
        return "[request " + requestId + " to " + targetName + " ended without a reply: "
                + cause.getClass().getSimpleName() + ": " + cause.getMessage() + "]";
    }

    /**
     * Sends the answer of a turn the provider started by itself, after the request it went on with
     * had been answered, to that request's asker as a follow-up
     * ({@code ReportReachesTheAskerAfterDelegation_261107_oo01}). Queued like a reply, under the same
     * request id, with the heading {@code [follow-up to request <id>, from <turn id>]}.
     *
     * @param system    the actor system both conversations live in
     * @param myName    the qualified name of the conversation that went on
     * @param askerName the qualified name of the conversation that sent the request
     * @param requestId the request the follow-up is about
     * @param turn      the answer of the turn that went on, and that turn's id
     */
    public static void followUp(IIActorSystem system, String myName, String askerName, String requestId,
                                TurnReply turn) {
        IIActorRef<?> askerActor = system.getIIActor(askerName + ".chat");
        ActorRef<PromptQueue> askerQueue = system.getActor(askerName + ".queue");
        if (!(askerActor instanceof ChatSessionIIAR askerChat) || askerQueue == null) {
            LOG.warning("follow-up to request " + requestId + " not sent: " + askerName + " is gone");
            return;
        }
        String text = "[follow-up to request " + requestId + ", from "
                + (turn.turnId() == null ? myName : turn.turnId()) + "]\n" + turn.reply();
        Consumer<ChatEvent> watchers = watchersOf(system, askerName);
        askerQueue.tell(q -> q.enqueue(text, null, "queue", watchers, askerChat.asChatSessionRef(),
                ChatSession.ASK_CHAT_REPLY_SOURCE_PREFIX + myName, null, new CompletableFuture<>(),
                false, true, java.util.List.of(), requestId));
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
        return askQualified(system, watchdog, myName, targetName, prompt, timeoutSeconds, outsideTurnRequestId(myName));
    }

    /**
     * As {@link #askQualified(IIActorSystem, ActorRef, String, String, String, Integer)}, under a request id the
     * caller made — a conversation's turn names its requests {@code <turn id>:r<n>}
     * ({@code TurnAndRequestIds_261107_oo01}).
     *
     * @param requestId the request's id
     */
    public static String askQualified(IIActorSystem system, ActorRef<CallWatchdog> watchdog,
                                       String myName, String targetName, String prompt,
                                       Integer timeoutSeconds, String requestId) {
        return askWithEnding(system, watchdog, myName, targetName, prompt, timeoutSeconds, requestId).reply();
    }

    /**
     * Numbers requests sent from outside any conversation's turn (a job's workflow step). Seeded with the
     * time the process started, so a number is not given out again after a restart.
     */
    private static final java.util.concurrent.atomic.AtomicLong OUTSIDE_TURN_REQUESTS =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    /**
     * @param myName the sender's name, e.g. a job's actor name
     * @return a request id for a request sent from outside any turn, {@code <sender>:q<n>}
     */
    static String outsideTurnRequestId(String myName) {
        return myName + ":q" + OUTSIDE_TURN_REQUESTS.incrementAndGet();
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
        return askWithEnding(system, watchdog, myName, targetName, prompt, timeoutSeconds, outsideTurnRequestId(myName));
    }

    static Outcome askWithEnding(IIActorSystem system, ActorRef<CallWatchdog> watchdog,
                                  String myName, String targetName, String prompt,
                                  Integer timeoutSeconds, String requestId) {
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
            CompletableFuture<TurnReply> done = new CompletableFuture<>();
            // The request id is unique within this instance, so it also serves as the result key. The
            // prompt goes as it is: a workflow step built it for the model, and the step gets the reply
            // from done, so no model needs the id in the text. The id is kept on the queue item, and so
            // in the record and on the screen (TurnAndRequestIds_261107_oo01).
            String resultKey = requestId;
            targetQueueRef.tell(q -> q.enqueue(prompt, null, "queue",
                    toWatchers,
                    targetChatSessionIIAR.asChatSessionRef(), ChatSession.ASK_CHAT_SOURCE_PREFIX + myName, resultKey,
                    done, false, true, java.util.List.of(), requestId));
            TurnReply turn;
            try {
                turn = done.get(waitTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                return Outcome.of("error: chat " + target + " did not respond within " + waitTimeoutSeconds + "s");
            }
            if (turn == null || turn.reply() == null) {
                return Outcome.of("error: no result from chat " + target
                        + (turn != null && turn.ending() != null ? ": " + turn.ending() : ""));
            }
            return new Outcome(turn.reply(), turn.ending());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ask_chat: call to " + target + " failed", e);
            return Outcome.of("error: ask_chat failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            watchdog.tell(w -> w.endWait(myName));
        }
    }
}
