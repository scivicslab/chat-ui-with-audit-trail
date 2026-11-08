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
    static final long DEFAULT_CHILD_REPLY_WARN_MINUTES = 30;

    /** Source of the notice put into the conversation's queue. */
    public static final String SOURCE = "agent:timekeeper";

    private final String conversation;
    private final ChatSessionIIAR chat;
    private final ActorRef<PromptQueue> queue;
    private final Consumer<ChatEvent> watchers;
    private long urgentWaitMillis;
    private long childReplyWarnMillis;
    private final Set<String> warned = new HashSet<>();
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
            for (OpenRequests.Outstanding o : chat.outstandingChildrenDirect()) {
                if (now - o.sentAt() > childReplyWarnMillis && warned.add(o.requestId())) {
                    String text = "[no reply yet to request " + o.requestId() + " sent to " + o.target() + ", "
                            + (now - o.sentAt()) / 60_000 + " minutes]\nDecide whether to ask it how far it has got "
                            + "(ask_chat_urgent), send the request again, or answer without it.";
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
