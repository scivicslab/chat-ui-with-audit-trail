package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.CallWatchdog;
import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.Organization;
import com.scivicslab.chatui.core.actor.PromptQueue;
import com.scivicslab.chatui.core.actor.SseConnection;
import com.scivicslab.chatui.core.actor.TurnReply;
import com.scivicslab.chatui.core.actor.Timekeeper;
import com.scivicslab.chatui.core.remote.RemoteNodes;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.distributed.Receipt;
import org.json.JSONObject;
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
        done.whenCompleteAsync((reply, failure) -> {
            // A request its sender took back with cancel_request leaves no message: the sender knows.
            if (reply != TurnReply.WITHDRAWN) {
                deliverReply(system, myName, myChat, myQueue, targetName, requestId, urgent, reply, failure);
            }
        });
        SENT.put(requestId, new Sent(myName, targetName, targetQueue, done, null));
        Consumer<ChatEvent> targetWatchers = watchersOf(system, targetName);
        // The heading says when the sender is the target's superior. It does not change the message's rank:
        // only ask_chat_urgent goes into the urgent rank, whoever sends it, so that a superior's new message
        // does not hold back replies that were waiting first (ConversationReportingLines_261108_oo01).
        boolean fromSuperior = isSuperiorOf(system, myProjectId, myChatId, targetName);
        String headed = "[" + (urgent ? "urgent request " : "request ") + requestId + " from " + myName
                + (fromSuperior ? ", your superior" : "") + "]\n" + prompt;
        int passed;
        try {
            // Asked rather than told: the result says how many waiting items an urgent request passed.
            // The queue is its own actor, so this does not wait for the target's turn.
            passed = targetQueue.ask(q -> q.enqueue(headed, null, "queue", targetWatchers,
                    targetChat.asChatSessionRef(), ChatSession.ASK_CHAT_SOURCE_PREFIX + myName, resultKey, done,
                    false, true, java.util.List.of(), requestId, urgent)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            SENT.remove(requestId);
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

    /**
     * A request this instance's conversations have sent, kept so that its sender can take it back.
     *
     * @param targetQueue the target's queue in this instance, or {@code null} when the target is on another node
     * @param node        the node the target is on, or {@code null} for this instance
     */
    private record Sent(String sender, String targetName, ActorRef<PromptQueue> targetQueue,
                        CompletableFuture<TurnReply> done, String node) {}

    /** How many sent requests are remembered; the oldest go first. */
    private static final int MAX_SENT = 2000;

    /** The requests sent with {@code ask_chat}, by request id, oldest first. */
    private static final java.util.Map<String, Sent> SENT = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>() {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Sent> eldest) {
                    return size() > MAX_SENT;
                }
            });

    /**
     * @param myName the calling conversation's qualified name
     * @return one line for each request it sent that has no reply yet, "{@code <id> to <target>}", oldest first
     */
    public static java.util.List<String> unansweredFrom(String myName) {
        java.util.List<String> out = new java.util.ArrayList<>();
        synchronized (SENT) {
            for (java.util.Map.Entry<String, Sent> e : SENT.entrySet()) {
                Sent s = e.getValue();
                if (s.sender().equals(myName) && !s.done().isDone()) out.add(e.getKey() + " to " + s.targetName());
            }
        }
        return out;
    }

    /**
     * The {@code cancel_request} tool: takes a request the caller sent with {@code ask_chat} out of the target's
     * queue, if it still waits there ({@code CancelARequestInTheQueue_261113_oo01}). Only the sender can: a
     * request id that the caller did not send is answered as not found. A request that has already started is
     * not stopped.
     *
     * @param system    the actor system the conversations live in
     * @param myName    the calling conversation's qualified name
     * @param requestId the id {@code ask_chat} gave back
     * @return a line starting with {@code removed}, {@code already running}, {@code already answered} or
     *         {@code not found}, saying what happened and what the caller can do next
     */
    public static String cancel(IIActorSystem system, String myName, String requestId) {
        if (requestId == null || requestId.isBlank()) return "error: requestId is required";
        Sent sent = SENT.get(requestId.trim());
        if (sent == null || !sent.sender().equals(myName)) {
            return "not found: you sent no request with the id " + requestId.trim() + " in this run of the "
                    + "instance. Check the id in the result of your ask_chat call.";
        }
        String id = requestId.trim();
        if (sent.done().isDone()) {
            return "already answered: request " + id + " has already been answered or ended, so there is nothing "
                    + "to take back. Its reply is, or was, a message in your queue.";
        }
        if (sent.node() != null) return cancelRemote(system, myName, id, sent);
        boolean removed;
        try {
            removed = sent.targetQueue().ask(q -> q.withdraw(id, ChatSession.ASK_CHAT_SOURCE_PREFIX + myName))
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "cancel_request: could not reach the queue of " + sent.targetName(), e);
            return "error: could not reach the queue of " + sent.targetName() + ": " + e.getClass().getSimpleName();
        }
        if (removed) {
            return "removed: request " + id + " was taken out of " + sent.targetName() + "'s queue before it ran. "
                    + "No reply will come for it; do not wait for one.";
        }
        if (sent.done().isDone()) {
            return "already answered: request " + id + " has already been answered or ended, so there is nothing "
                    + "to take back. Its reply is, or was, a message in your queue.";
        }
        return "already running: " + sent.targetName() + " has started request " + id + ", and it is not stopped. "
                + "Its reply will arrive as usual; if the work is no longer wanted, send " + sent.targetName()
                + " a follow-up request saying so.";
    }

    /** @return whether the sending conversation is the target's superior in their project's organization */
    public static boolean isSuperiorOf(IIActorSystem system, String myProjectId, String myChatId, String targetName) {
        String prefix = myProjectId + "/chat-";
        if (!targetName.startsWith(prefix)) return false;
        ActorRef<Organization> org = system.getActor(ChatUiActorSystem.organizationActorName(myProjectId));
        if (org == null) return false;
        String target = targetName.substring(prefix.length());
        try {
            return org.ask(o -> o.isSuperiorOf(myChatId, target)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ask_chat: could not read the organization of " + myProjectId, e);
            return false;
        }
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
     * Sends the answer of a turn a reminder started to the asker of the request the reminder names, as a
     * follow-up ({@code ReplyDecisionAndPromisedReport_261108_oo01}). Queued like a reply, under the same
     * request id, with the heading {@code [follow-up to request <id>, from <turn id>, reminder <n>]}, so the
     * asker records a decision on it as on any reply.
     *
     * @param system    the actor system both conversations live in
     * @param myName    the qualified name of the conversation that was reminded
     * @param askerName the qualified name of the conversation waiting for the report
     * @param requestId the request the follow-up is about
     * @param turn      the answer of the turn the reminder started, and that turn's id
     * @param reminder  which reminder it was for the request, counting from 1
     */
    public static void followUp(IIActorSystem system, String myName, String askerName, String requestId,
                                TurnReply turn, int reminder) {
        Received from = RECEIVED.get(requestId);
        if (from != null && from.sender().equals(askerName)) {
            String body = turn.reply() != null ? turn.reply()
                    : "its turn left no answer" + (turn.ending() == null ? "" : ": " + turn.ending());
            // The sender knows the request by its own id, not by this instance's local one.
            String text = "[follow-up to request " + from.requestId() + ", from "
                    + (turn.turnId() == null ? myName : turn.turnId()) + ", reminder " + reminder + "]\n" + body;
            sendOrLog(from.nodes(), from.senderNode(), "enqueueFollowUp", new JSONObject().put("requestId", from.requestId())
                    .put("asker", askerName).put("from", myName).put("text", text), "follow-up to request " + from.requestId());
            return;
        }
        IIActorRef<?> askerActor = system.getIIActor(askerName + ".chat");
        ActorRef<PromptQueue> askerQueue = system.getActor(askerName + ".queue");
        if (!(askerActor instanceof ChatSessionIIAR askerChat) || askerQueue == null) {
            LOG.warning("follow-up to request " + requestId + " not sent: " + askerName + " is gone");
            return;
        }
        String body = turn.reply() != null ? turn.reply()
                : "its turn left no answer" + (turn.ending() == null ? "" : ": " + turn.ending());
        String text = "[follow-up to request " + requestId + ", from "
                + (turn.turnId() == null ? myName : turn.turnId()) + ", reminder " + reminder + "]\n" + body;
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
     * phases resolving their {@code "worker"} through {@link com.scivicslab.chatui.core.actor.RoleTable}
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

    // ---- Requests between conversations of two chat-ui instances (ConversationSquadsAcrossInstances_261115_oo01).
    // The sender's side keeps the request in SENT with the node it went to; the target's side keeps it in RECEIVED,
    // so that the reply, a follow-up and a cancel find their way back. Everything goes as one message to the other
    // instance's gateway actor, sent again under the same message id until it is received; the other side drops a
    // second copy.

    /**
     * A request another instance's conversation sent to one of this instance's conversations.
     *
     * @param requestId the id the sender gave it, unique only on the sender's node
     */
    private record Received(RemoteNodes nodes, String senderNode, String requestId, String sender, String target,
                            CompletableFuture<TurnReply> done) {}

    /**
     * The id under which this instance keeps a request from another node: in the target's queue, in the target's
     * open requests, in its timekeeper's promises, and in {@link #RECEIVED}. A request id is unique only on the node
     * that made it, so two nodes can send the same one; with the sender's node in front, they stay apart.
     */
    static String localIdOf(String senderNode, String requestId) {
        return senderNode + "|" + requestId;
    }

    /** The requests that came from other instances, by {@link #localIdOf}, oldest first. */
    private static final java.util.Map<String, Received> RECEIVED = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>() {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Received> eldest) {
                    return size() > MAX_SENT;
                }
            });

    /**
     * {@code ask_chat} and {@code ask_chat_urgent} with a {@code node}: sends the request to a conversation of
     * another chat-ui instance. Returns once that instance has received it; the reply comes back as a new message,
     * as for a request inside this instance.
     *
     * @param node the node the target conversation is on, as named in {@code chat-ui.peers}
     * @return a line saying the request was sent and under which id, or an {@code error: ...} string
     */
    public static String sendRemote(IIActorSystem system, String myProjectId, String myChatId, String node,
                                    String target, String prompt, String requestId, boolean urgent) {
        if (target == null || target.isBlank()) return "error: chatId is required";
        if (prompt == null || prompt.isBlank()) return "error: prompt is required";
        RemoteNodes nodes = RemoteNodes.of(system);
        if (nodes == null) {
            return "error: node " + node + " is not available: this instance has no other chat-ui instance "
                    + "configured. Leave node out to reach a conversation of this instance.";
        }
        String nodeName = node.strip();
        if (!nodes.knows(nodeName)) {
            return "error: " + nodes.notKnown(nodeName);
        }
        String myName = ChatUiActorSystem.chatActorName(myProjectId, myChatId);
        IIActorRef<?> mine = system.getIIActor(myName + ".chat");
        if (!(mine instanceof ChatSessionIIAR)) {
            return "error: the calling conversation " + myName + " is not registered";
        }
        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        SENT.put(requestId, new Sent(myName, targetName, null, done, nodeName));
        JSONObject m = new JSONObject().put("requestId", requestId).put("sender", myName)
                .put("senderNode", nodes.myName()).put("target", targetName).put("prompt", prompt).put("urgent", urgent);
        Receipt receipt = nodes.send(nodeName, "enqueueRequest", m.toString());
        if (!receipt.accepted()) {
            SENT.remove(requestId);
            return "error: node " + nodeName + " did not receive the request: " + receipt.detail();
        }
        return "sent to " + targetName + " on node " + nodeName + " as " + (urgent ? "urgent request " : "request ")
                + requestId + "; its reply will arrive in this conversation as a new message when its turn ends";
    }

    /**
     * {@code expect_report} with a {@code node}: registers the report a conversation of another instance promised
     * with that conversation's timekeeper. The reminder's answer comes back as a follow-up.
     *
     * @return a line saying the promise was sent, or an {@code error: ...} string
     */
    public static String expectReportRemote(IIActorSystem system, String myName, String node, String targetName,
                                            String requestId, long minutes) {
        RemoteNodes nodes = RemoteNodes.of(system);
        if (nodes == null) {
            return "error: node " + node + " is not available: this instance has no other chat-ui instance configured";
        }
        String nodeName = node.strip();
        if (!nodes.knows(nodeName)) {
            return "error: " + nodes.notKnown(nodeName);
        }
        Receipt receipt = nodes.send(nodeName, "registerPromise", new JSONObject().put("target", targetName)
                .put("requestId", requestId).put("asker", myName).put("senderNode", nodes.myName())
                .put("minutes", minutes).toString());
        if (!receipt.accepted()) {
            return "error: node " + nodeName + " did not receive the promise: " + receipt.detail();
        }
        return "ok: " + targetName + " on node " + nodeName + ": its timekeeper will remind it in " + minutes
                + " minutes to report on request " + requestId + "; its answer will come to you as a follow-up";
    }

    /** Asks the target's instance to take a request back; what became of it comes back as a new message. */
    private static String cancelRemote(IIActorSystem system, String myName, String id, Sent sent) {
        RemoteNodes nodes = RemoteNodes.of(system);
        if (nodes == null) return "error: the remote path is not running, so request " + id + " cannot be taken back";
        Receipt receipt = nodes.send(sent.node(), "withdrawRequest", new JSONObject().put("requestId", id)
                .put("sender", myName).put("senderNode", nodes.myName()).toString());
        if (!receipt.accepted()) {
            return "error: node " + sent.node() + " did not receive the cancel: " + receipt.detail();
        }
        return "cancel sent: request " + id + " went to " + sent.targetName() + " on node " + sent.node()
                + ". Whether it was removed arrives as a new message: \"removed\" means no reply will come.";
    }

    /** On the target's instance: a request from another instance goes into the target's queue. */
    public static ActionResult remoteRequestArrived(IIActorSystem local, RemoteNodes nodes, JSONObject m) {
        String requestId = m.getString("requestId");
        String sender = m.getString("sender");
        String senderNode = m.getString("senderNode");
        String target = m.getString("target");
        boolean urgent = m.optBoolean("urgent", false);
        LOG.info("remote request " + requestId + " from " + sender + " on node " + senderNode + " for " + target);
        IIActorRef<?> targetActor = local.getIIActor(target + ".chat");
        ActorRef<PromptQueue> targetQueue = local.getActor(target + ".queue");
        if (!(targetActor instanceof ChatSessionIIAR targetChat) || targetQueue == null) {
            String notFound = "conversation not found on node " + nodes.myName() + ": " + target;
            LOG.warning("remote request " + requestId + " from node " + senderNode + " refused: " + notFound);
            sendOrLog(nodes, senderNode, "enqueueReply", new JSONObject().put("requestId", requestId).put("asker", sender)
                    .put("from", target).put("urgent", urgent).put("text", "[request " + requestId + " to " + target
                            + " ended without a reply: " + notFound + "]"),
                    "no-such-conversation reply to request " + requestId);
            return new ActionResult(false, notFound);
        }
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        String localId = localIdOf(senderNode, requestId);
        RECEIVED.put(localId, new Received(nodes, senderNode, requestId, sender, target, done));
        done.whenCompleteAsync((reply, failure) -> {
            if (reply == TurnReply.WITHDRAWN) return;
            sendOrLog(nodes, senderNode, "enqueueReply", new JSONObject().put("requestId", requestId).put("asker", sender)
                    .put("from", target).put("urgent", urgent).put("text", replyMessage(target, requestId, reply, failure)),
                    "reply to request " + requestId);
        });
        String headed = "[" + (urgent ? "urgent request " : "request ") + requestId + " from " + sender
                + " on node " + senderNode + "]\n" + m.getString("prompt");
        Consumer<ChatEvent> watchers = watchersOf(local, target);
        try {
            targetQueue.ask(q -> q.enqueue(headed, null, "queue", watchers, targetChat.asChatSessionRef(),
                    ChatSession.ASK_CHAT_SOURCE_PREFIX + sender, localId, done, false, true, java.util.List.of(),
                    localId, urgent)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            RECEIVED.remove(localId);
            LOG.log(Level.WARNING, "remote request " + requestId + " could not be queued for " + target, e);
            return new ActionResult(false, "could not queue the request: " + e.getClass().getSimpleName());
        }
        return new ActionResult(true, "queued for " + target);
    }

    /** On the sender's instance: the reply goes into the sender's queue, as a reply inside one instance does. */
    public static ActionResult remoteReplyArrived(IIActorSystem local, JSONObject m) {
        String requestId = m.getString("requestId");
        String from = m.getString("from");
        ActionResult queued = enqueueFor(local, m.getString("asker"), m.getString("text"),
                ChatSession.ASK_CHAT_REPLY_SOURCE_PREFIX + from, requestId, m.optBoolean("urgent", false));
        Sent sent = SENT.get(requestId);
        if (sent != null && sent.node() != null) sent.done().complete(new TurnReply(null, "answered on node " + sent.node(), null));
        return queued;
    }

    /** On the sender's instance: a follow-up to a request goes into the sender's queue. */
    public static ActionResult remoteFollowUpArrived(IIActorSystem local, JSONObject m) {
        return enqueueFor(local, m.getString("asker"), m.getString("text"),
                ChatSession.ASK_CHAT_REPLY_SOURCE_PREFIX + m.getString("from"), m.getString("requestId"), false);
    }

    /** On the target's instance: the sender takes a request back, if it still waits in the queue. */
    public static ActionResult remoteWithdraw(IIActorSystem local, RemoteNodes nodes, JSONObject m) {
        String requestId = m.getString("requestId");
        String sender = m.getString("sender");
        String localId = localIdOf(m.getString("senderNode"), requestId);
        Received r = RECEIVED.get(localId);
        String result;
        String target;
        if (r == null || !r.sender().equals(sender)) {
            result = "not found";
            target = "?";
        } else {
            target = r.target();
            boolean removed = false;
            ActorRef<PromptQueue> queue = local.getActor(target + ".queue");
            try {
                if (queue != null) {
                    removed = queue.ask(q -> q.withdraw(localId, ChatSession.ASK_CHAT_SOURCE_PREFIX + sender))
                            .get(5, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "remote cancel of " + requestId + " could not reach the queue of " + target, e);
            }
            result = removed ? "removed" : r.done().isDone() ? "already answered" : "already running";
        }
        sendOrLog(nodes, m.getString("senderNode"), "enqueueCancelResult", new JSONObject().put("requestId", requestId)
                .put("asker", sender).put("from", target).put("result", result), "cancel result of " + requestId);
        return new ActionResult(true, result);
    }

    /** On the sender's instance: what became of a request it took back, as a message of its own. */
    public static ActionResult remoteCancelResult(IIActorSystem local, JSONObject m) {
        String requestId = m.getString("requestId");
        String asker = m.getString("asker");
        String result = m.getString("result");
        Sent sent = SENT.get(requestId);
        String where = sent == null ? m.optString("from", "?") : sent.targetName() + " on node " + sent.node();
        String meaning = switch (result) {
            case "removed" -> "It was taken out of the queue before it ran. No reply will come for it; do not wait for one.";
            case "already running" -> "It has started and is not stopped. Its reply will arrive as usual.";
            case "already answered" -> "It was already answered; its reply is, or was, a message in your queue.";
            default -> "The other instance has no such request from you.";
        };
        if ("removed".equals(result)) {
            if (sent != null) sent.done().complete(TurnReply.WITHDRAWN);
            IIActorRef<?> askerActor = local.getIIActor(asker + ".chat");
            if (askerActor instanceof ChatSessionIIAR askerChat) {
                askerChat.asChatSessionRef().tell(c -> c.remoteRequestWithdrawn(requestId));
            }
        }
        return enqueueFor(local, asker, "[cancel result for request " + requestId + " to " + where + ": " + result
                + "]\n" + meaning, CANCEL_RESULT_SOURCE_PREFIX + m.optString("from", "?"), null, false);
    }

    /** Source of the message that says what became of a request taken back across instances. */
    public static final String CANCEL_RESULT_SOURCE_PREFIX = "agent:cancel-result:";

    /** On the instance of the conversation that promised a report: registers it with that conversation's timekeeper. */
    public static ActionResult remoteRegisterPromise(IIActorSystem local, JSONObject m) {
        String target = m.getString("target");
        ActorRef<Timekeeper> keeper = local.getActor(target + ".timekeeper");
        if (keeper == null) return new ActionResult(false, "no conversation " + target);
        // The promise is kept under the local id of the request it is about, so the reminder's answer finds its
        // way back to the node that asked (followUp).
        String requestId = m.has("senderNode") ? localIdOf(m.getString("senderNode"), m.getString("requestId"))
                : m.getString("requestId");
        String asker = m.getString("asker");
        long minutes = m.getLong("minutes");
        try {
            int n = keeper.ask(k -> k.expectReport(requestId, asker, minutes)).get(5, TimeUnit.SECONDS);
            return new ActionResult(true, "reminder " + n);
        } catch (Exception e) {
            return new ActionResult(false, "could not register: " + e.getClass().getSimpleName());
        }
    }

    /** Puts one message in a conversation's queue, as a reply inside one instance is put there. */
    private static ActionResult enqueueFor(IIActorSystem local, String chatName, String text, String source,
                                           String requestId, boolean urgent) {
        IIActorRef<?> chat = local.getIIActor(chatName + ".chat");
        ActorRef<PromptQueue> queue = local.getActor(chatName + ".queue");
        if (!(chat instanceof ChatSessionIIAR c) || queue == null) {
            LOG.warning("message for " + chatName + " dropped: no such conversation here");
            return new ActionResult(false, "no conversation " + chatName);
        }
        Consumer<ChatEvent> watchers = watchersOf(local, chatName);
        queue.tell(q -> q.enqueue(text, null, "queue", watchers, c.asChatSessionRef(), source, null,
                new CompletableFuture<>(), false, true, java.util.List.of(), requestId, urgent));
        return new ActionResult(true, "queued for " + chatName);
    }

    /** Sends one message to another instance and logs it when that instance never received it. */
    private static void sendOrLog(RemoteNodes nodes, String node, String action, JSONObject m, String what) {
        try {
            Receipt receipt = nodes.send(node, action, m.toString());
            if (!receipt.accepted()) LOG.warning(what + " not received by node " + node + ": " + receipt.detail());
        } catch (Exception e) {
            LOG.log(Level.WARNING, what + " could not be sent to node " + node, e);
        }
    }
}
