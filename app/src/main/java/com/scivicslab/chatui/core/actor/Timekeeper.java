package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.core.ActorRef;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.chatui.core.rest.ChatEvent;

/**
 * One conversation's watch over time ({@code TimekeeperPausesATurnForAnUrgentRequest_261107_oo01}). A child of
 * the conversation, deleted with it, and checked every {@link TimekeeperTicker#TICK_SECONDS} seconds.
 *
 * <p>It does two things, both to its own conversation only:</p>
 * <ul>
 * <li>When the conversation is running a turn and the urgent item at the front of its queue has waited longer
 * than {@link #urgentWaitMillis}, it pauses that turn ({@link ChatSession#pauseForUrgent}). The urgent request is
 * answered next, and the paused work goes on after it.</li>
 * <li>When a request the conversation sent has had no reply for longer than {@link #childReplyWarnMillis}, it puts
 * one notice saying so into the conversation's queue. What to do about it is the conversation's model's call.</li>
 * <li>When a report the conversation promised was registered with {@code expect_report} and its time has come,
 * it puts one reminder into the conversation's queue, naming the request; the answer of the turn the reminder
 * starts goes to the asker as a follow-up ({@code ReplyDecisionAndPromisedReport_261108_oo01}).</li>
 * </ul>
 *
 * <p>It reads the conversation's state without waiting for a running turn ({@link ChatSessionIIAR#isBusyDirect},
 * {@link ChatSessionIIAR#outstandingChildrenDirect}), and reaches the turn with {@code tellNow}, as the queue's
 * {@code cancel_and_send} does: a running turn holds the conversation's actor thread.</p>
 */
public class Timekeeper {

    private static final Logger LOG = Logger.getLogger(Timekeeper.class.getName());

    /** Default for how long an urgent request may wait at the front before the running turn is paused. */
    static final long DEFAULT_URGENT_WAIT_MINUTES = 10;

    /** Default for how long a request may go without a reply before the conversation is told. */
    static final long DEFAULT_CHILD_REPLY_WARN_MINUTES = 5;

    /**
     * Default wait for a reminder the end-of-turn step registers when the answer states no time of its own
     * ({@code ReminderDecidedAtTheEndOfTheTurn_261114_oo01}).
     */
    public static final long DEFAULT_PROMISE_REMINDER_MINUTES = 5;

    /** Source of the notice put into the conversation's queue. */
    public static final String SOURCE = "agent:timekeeper";

    private final String conversation;
    private final ChatSessionIIAR chat;
    private final ActorRef<PromptQueue> queue;
    private final Consumer<ChatEvent> watchers;
    private long urgentWaitMillis;
    private long childReplyWarnMillis;
    private final java.util.function.Function<String, TargetState> targetStates;
    private final Set<String> warned = new HashSet<>();
    /**
     * When the reply-wait watch of a request was armed again, at the end of a turn after its notice
     * ({@code ReplyWatchRearmedAtTheEndOfEveryTurn_261116_oo01}). A request not here is watched from when it was sent.
     * One entry per request, so a request never has more than one watch pending.
     */
    private final java.util.Map<String, Long> rearmedAt = new java.util.HashMap<>();
    private final PromisedReports promised = new PromisedReports();

    /**
     * @param conversation         the conversation's qualified name
     * @param chat                 its ChatSession bridge
     * @param queue                its queue
     * @param watchers             where what the conversation says goes (its SSE), or a no-op
     * @param urgentWaitMillis     how long an urgent request may wait at the front of a running conversation
     * @param childReplyWarnMillis how long a sent request may go without a reply before the conversation is told
     */
    public Timekeeper(String conversation, ChatSessionIIAR chat, ActorRef<PromptQueue> queue,
                      Consumer<ChatEvent> watchers, long urgentWaitMillis, long childReplyWarnMillis) {
        this(conversation, chat, queue, watchers, urgentWaitMillis, childReplyWarnMillis, name -> TargetState.UNKNOWN);
    }

    /**
     * What a notice says about the conversation a request went to: whether it is running a turn, and since when.
     *
     * @param known        whether the target could be read at all
     * @param running      whether it is running a turn now
     * @param runningSince when the running turn started, in milliseconds since the epoch; meaningful when running
     */
    public record TargetState(boolean known, boolean running, long runningSince) {
        /** The target could not be read. */
        public static final TargetState UNKNOWN = new TargetState(false, false, 0);
    }

    /**
     * @param targetStates reads the state of a conversation by its qualified name, without waiting for its turn
     */
    public Timekeeper(String conversation, ChatSessionIIAR chat, ActorRef<PromptQueue> queue,
                      Consumer<ChatEvent> watchers, long urgentWaitMillis, long childReplyWarnMillis,
                      java.util.function.Function<String, TargetState> targetStates) {
        this.targetStates = targetStates;
        this.conversation = conversation;
        this.chat = chat;
        this.queue = queue;
        this.watchers = watchers;
        this.urgentWaitMillis = urgentWaitMillis;
        this.childReplyWarnMillis = childReplyWarnMillis;
    }

    /**
     * Changes this conversation's limits ({@code TimekeeperPausesATurnForAnUrgentRequest_261107_oo01}).
     *
     * @param urgentWaitMinutes     how long an urgent request may wait at the front of a running turn, or
     *                              {@code null} to keep the current value
     * @param childReplyWarnMinutes how long a sent request may go without a reply, or {@code null} to keep it
     */
    public void setLimits(Long urgentWaitMinutes, Long childReplyWarnMinutes) {
        if (urgentWaitMinutes != null) urgentWaitMillis = TimeUnit.MINUTES.toMillis(urgentWaitMinutes);
        if (childReplyWarnMinutes != null) childReplyWarnMillis = TimeUnit.MINUTES.toMillis(childReplyWarnMinutes);
    }

    /** @return how long, in minutes, an urgent request may wait at the front of a running turn */
    public long urgentWaitMinutes() {
        return TimeUnit.MILLISECONDS.toMinutes(urgentWaitMillis);
    }

    /** @return how long, in minutes, a sent request may go without a reply before the conversation is told */
    public long childReplyWarnMinutes() {
        return TimeUnit.MILLISECONDS.toMinutes(childReplyWarnMillis);
    }

    /**
     * Registers a report this conversation promised, to be asked for once after {@code minutes}
     * ({@code ReplyDecisionAndPromisedReport_261108_oo01}).
     *
     * @param requestId the request the report was promised for, or {@code null} for a promise made to the person
     * @param asker     the qualified name of the conversation waiting for it, or {@code null} for the person
     * @param minutes   how long to wait before asking, in minutes
     * @return which reminder this will be for the request, counting from 1
     */
    public int expectReport(String requestId, String asker, long minutes) {
        return promised.expect(requestId, asker, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes));
    }

    /** @return the promised reports waiting for their time, soonest first, for the record and for tests */
    public List<PromisedReports.Pending> pendingPromises() {
        return promised.snapshot();
    }

    /**
     * What this timekeeper is watching at one moment, for the screen ({@code TimekeeperViewInTheActorTree_261115_oo01}).
     * Times that lie ahead or behind are given as milliseconds from now: positive is still to come, negative is
     * already past (the next check acts on it).
     *
     * @param conversation          the conversation's qualified name
     * @param urgentWaitMinutes     how long an urgent request may wait at the front of a running turn
     * @param childReplyWarnMinutes how long a sent request may go without a reply before the conversation is told
     * @param running               whether the conversation is running a turn
     * @param runningSince          when that turn started, in milliseconds since the epoch, or {@code null}
     * @param urgentWaitingMillis   how long the urgent item at the front has waited, or {@code null} when none waits
     * @param pauseInMillis         how long until the running turn would be paused for it, or {@code null} when no
     *                              urgent item waits or no turn runs
     * @param sent                  the requests this conversation sent that have no reply yet, oldest first
     * @param promised              the promised reports waiting for their time, soonest first
     */
    public record Watch(String conversation, long urgentWaitMinutes, long childReplyWarnMinutes, boolean running,
                        Long runningSince, Long urgentWaitingMillis, Long pauseInMillis,
                        List<Sent> sent, List<Promised> promised) {

        /** @return whether an urgent item waits at the front of the queue */
        public boolean urgentWaiting() {
            return urgentWaitingMillis != null;
        }

        /**
         * A request sent and not yet answered.
         *
         * @param requestId         the request's id
         * @param target            the qualified name of the conversation it went to, or {@code null} when not known
         * @param outMillis         how long it has been out
         * @param noticeSent        whether the "no reply yet" notice has already been put into the queue
         * @param noticeDueInMillis how long until that notice is due (negative once due)
         */
        public record Sent(String requestId, String target, long outMillis, boolean noticeSent, long noticeDueInMillis) {}

        /**
         * A promised report waiting for its time.
         *
         * @param requestId   the request it was promised for, or {@code null} for a promise to the person
         * @param asker       the conversation waiting for it, or {@code null} for the person
         * @param dueInMillis how long until the reminder (negative once due)
         * @param number      which reminder it will be for the request, counting from 1
         */
        public record Promised(String requestId, String asker, long dueInMillis, int number) {}
    }

    /**
     * Builds what the screen shows from the state the timekeeper reads. A plain function, so that it is tested
     * without actors.
     *
     * @param urgentQueuedAt when the urgent item at the front of the queue entered it, or {@code null} when none waits
     * @param warned         the requests whose notice has been put into the queue
     */
    static Watch summarize(String conversation, long urgentWaitMillis, long childReplyWarnMillis, boolean running,
                           Long runningSince, Long urgentQueuedAt, List<OpenRequests.Outstanding> out,
                           Set<String> warned, List<PromisedReports.Pending> promised, long now) {
        return summarize(conversation, urgentWaitMillis, childReplyWarnMillis, running, runningSince, urgentQueuedAt, out,
                warned, java.util.Map.of(), promised, now);
    }

    /**
     * As above, with the times the watches were armed again ({@code ReplyWatchRearmedAtTheEndOfEveryTurn_261116_oo01}).
     *
     * @param rearmedAt when each request's watch was armed again, by request id
     */
    static Watch summarize(String conversation, long urgentWaitMillis, long childReplyWarnMillis, boolean running,
                           Long runningSince, Long urgentQueuedAt, List<OpenRequests.Outstanding> out,
                           Set<String> warned, java.util.Map<String, Long> rearmedAt,
                           List<PromisedReports.Pending> promised, long now) {
        Long waited = urgentQueuedAt == null ? null : now - urgentQueuedAt;
        Long pauseIn = waited != null && running ? urgentWaitMillis - waited : null;
        List<Watch.Sent> sent = out.stream()
                .sorted((a, b) -> Long.compare(a.sentAt(), b.sentAt()))
                .map(o -> new Watch.Sent(o.requestId(), o.target(), now - o.sentAt(), warned.contains(o.requestId()),
                        watchedFrom(o, rearmedAt) + childReplyWarnMillis - now))
                .toList();
        List<Watch.Promised> due = promised.stream()
                .sorted((a, b) -> Long.compare(a.dueAt(), b.dueAt()))
                .map(p -> new Watch.Promised(p.requestId(), p.asker(), p.dueAt() - now, p.number()))
                .toList();
        return new Watch(conversation, TimeUnit.MILLISECONDS.toMinutes(urgentWaitMillis),
                TimeUnit.MILLISECONDS.toMinutes(childReplyWarnMillis), running, running ? runningSince : null,
                waited, pauseIn, sent, due);
    }

    /**
     * What this timekeeper watches now. It reads the conversation without waiting for a running turn, as
     * {@link #tick} does, and changes nothing.
     *
     * @param now the current time, in milliseconds since the epoch
     */
    public Watch watch(long now) {
        Long urgentQueuedAt = null;
        try {
            urgentQueuedAt = queue.ask(PromptQueue::frontUrgentQueuedAt).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING, conversation + ": the queue did not say whether an urgent item waits", e);
        }
        boolean running = chat.isBusyDirect();
        return summarize(conversation, urgentWaitMillis, childReplyWarnMillis, running, chat.runningSinceDirect(),
                urgentQueuedAt, chat.outstandingChildrenDirect(), warned, rearmedAt, promised.snapshot(), now);
    }

    /**
     * One check, at {@code now}.
     *
     * @param now the current time, in milliseconds since the epoch
     */
    public void tick(long now) {
        try {
            if (chat.isBusyDirect()) {
                Long since = queue.ask(PromptQueue::frontUrgentQueuedAt).get(5, TimeUnit.SECONDS);
                if (since != null && now - since > urgentWaitMillis) {
                    LOG.info(conversation + ": an urgent request has waited " + (now - since) / 1000
                            + "s; pausing the running turn");
                    chat.asChatSessionRef().tellNow(ChatSession::pauseForUrgent);
                }
            }
            List<OpenRequests.Outstanding> out = chat.outstandingChildrenDirect();
            // A request that came back and was reopened is warned about again, counting from its reopening.
            warned.removeIf(id -> out.stream().noneMatch(o -> o.requestId().equals(id)));
            rearmedAt.keySet().removeIf(id -> out.stream().noneMatch(o -> o.requestId().equals(id)));
            for (OpenRequests.Outstanding o : out) {
                if (now - watchedFrom(o, rearmedAt) > childReplyWarnMillis && warned.add(o.requestId())) {
                    TargetState state;
                    try {
                        state = targetStates.apply(o.target());
                    } catch (Exception e) {
                        state = TargetState.UNKNOWN;
                    }
                    String text = noticeText(o.requestId(), o.target(), (now - o.sentAt()) / 60_000, state, now);
                    queue.tell(q -> q.enqueue(text, null, "queue", watchers, chat.asChatSessionRef(), SOURCE, null,
                            new CompletableFuture<>(), false, true, List.of(), null, false));
                }
            }
            for (PromisedReports.Reminder r : promised.due(now)) {
                String text = reminderText(r);
                String source = ChatSession.REMINDER_SOURCE_PREFIX + r.number() + ":"
                        + (r.toScreen() ? ChatSession.REMINDER_TO_SCREEN : r.asker());
                LOG.info(conversation + ": reminder " + r.number() + " for request " + r.requestId());
                queue.tell(q -> q.enqueue(text, null, "queue", watchers, chat.asChatSessionRef(), source, null,
                        new CompletableFuture<>(), false, true, List.of(), r.requestId(), false));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, conversation + ": timekeeper check failed", e);
        }
    }

    /**
     * Arms again the reply-wait watch of every open request whose notice has been given, so that another notice comes
     * one interval after now; a request whose watch is still pending keeps it, so it never has two
     * ({@code ReplyWatchRearmedAtTheEndOfEveryTurn_261116_oo01}). Called by the step at the end of every turn of the
     * conversation, so a request that stays open is reminded once per interval until its reply arrives, it is
     * cancelled, or it is closed. Watches of requests no longer open are dropped.
     *
     * @param open the requests the conversation sent that are still open
     * @param now  the current time, in milliseconds since the epoch
     * @return one line per open request, saying what was done with its watch
     */
    public List<String> rearmReplyWatches(List<OpenRequests.Outstanding> open, long now) {
        Set<String> ids = new HashSet<>();
        for (OpenRequests.Outstanding o : open) ids.add(o.requestId());
        warned.retainAll(ids);
        rearmedAt.keySet().retainAll(ids);
        List<String> lines = new java.util.ArrayList<>();
        for (OpenRequests.Outstanding o : open) {
            if (warned.remove(o.requestId())) {
                rearmedAt.put(o.requestId(), now);
                lines.add("reply watch re-armed: " + o.requestId());
            } else {
                lines.add("reply watch already pending: " + o.requestId());
            }
        }
        return lines;
    }

    /** @return when a request's current watch started: when it was sent or reopened, or when it was armed again */
    private static long watchedFrom(OpenRequests.Outstanding o, java.util.Map<String, Long> rearmedAt) {
        Long again = rearmedAt.get(o.requestId());
        return again == null ? o.sentAt() : Math.max(again, o.sentAt());
    }

    /**
     * The notice that a sent request has had no reply: which request, to whom, and for how long, so the
     * conversation can judge from what it already knows, and plainly that ignoring it is a valid answer. The
     * timekeeper does not suppress, merge or space out these notices; each one is the conversation's to judge.
     */
    static String noticeText(String requestId, String target, long minutesOut, TargetState state, long now) {
        String where = !state.known() ? target + "'s state could not be read."
                : state.running() ? target + " is running a turn, started " + Math.max(0, (now - state.runningSince()) / 60_000)
                        + " minutes ago."
                : target + " is not running a turn.";
        return "[no reply yet to request " + requestId + " sent to " + target + ", " + minutesOut + " minutes]\n"
                + "Request " + requestId + " to " + target + " has been out " + minutesOut + " minutes. " + where
                + " A request that takes this long may be working normally. Ignoring this notice is a valid answer: if nothing "
                + "needs doing, end the turn with one short line and make no tool calls. If you do act, use only what "
                + "you already know, for example tell the person how far the work has got, and do not create extra "
                + "work (no new request just to ask for progress).";
    }

    /** The reminder the conversation reads: which request, who waits, and what to write. */
    static String reminderText(PromisedReports.Reminder r) {
        String ask = " If the report is ready, write it, even if you wrote it before. If not, say how far you have got "
                + "and how long it will take.";
        if (r.toScreen()) {
            return "[reminder " + r.number() + "]\nYou told the person you would report later." + ask;
        }
        return "[reminder " + r.number() + " for request " + r.requestId() + " from " + r.asker() + "]\nYou told "
                + r.asker() + " you would report later on this request." + ask;
    }
}
