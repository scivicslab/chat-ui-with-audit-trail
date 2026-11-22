package com.scivicslab.chatui.core.actor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;

/**
 * Finds conversations whose turn is stalled and brings them back, one at a time or all at once
 * ({@code tasks/stalled-turn-recovery.md}).
 *
 * <p><b>Stalled.</b> A turn is stalled when the model call it is waiting for has produced nothing at all, not one
 * line, for {@code chat-ui.stall.threshold-minutes} since the call began. A call that streams, thinks, or runs a tool
 * is not stalled however long it takes.</p>
 *
 * <p><b>Recovery of one conversation.</b> (1) Its queue is held, so nothing waiting starts. (2) The running turn is
 * stopped as a cancel stops it ({@link ChatSession#stopForRecovery}). (3) If the turn has not ended within
 * {@link #stopWaitMillis}, the provider's process is killed ({@link ChatSession#forceStopProvider}) and the turn stops
 * waiting for the call ({@link ChatSession#abandonCallForRecovery}). (4) When the turn ends, the prompt it was working
 * on goes back to the front of the queue, unanswered, with its asker still waiting, and the queue is released
 * ({@link PromptQueue#returnRunningToFront}): the prompt runs again, then everything behind it in the order it had.
 * A Claude Code conversation starts its CLI again on the same session ({@code --resume}). One call brings the
 * conversation back; it does not have to be repeated.</p>
 *
 * <p><b>Usage limit.</b> A turn the model's service refused for the account's usage limit does not fail its request:
 * the request goes back to the front and the queue is held ({@link PromptQueue#returnRunningToFront}), so the waiting
 * requests are not failed one after another while the limit lasts. Such a conversation is listed with the reason
 * {@code usage-limit} at once, and recovering it releases its queue; if the limit still holds, the next turn is
 * refused and the queue is held again.</p>
 *
 * <p><b>Automatic.</b> With {@code chat-ui.stall.auto-recover-minutes} above 0, a turn that has been stalled that many
 * minutes (counted after the threshold) is recovered by the schedule, every {@link #TICK_SECONDS} seconds. 0 or less,
 * the default, recovers nothing by itself.</p>
 *
 * <p>An actor of its own under the housekeeper: the schedule, the REST resource and the conversation tools all reach
 * it through its mailbox, so two recoveries never run at once.</p>
 */
public class StalledTurnRecovery {

    private static final Logger LOG = Logger.getLogger(StalledTurnRecovery.class.getName());

    /** The actor's name, under which the conversation tools find it. */
    public static final String ACTOR_NAME = "stalled-turns";

    /** How often the schedule looks for turns to recover automatically. */
    static final long TICK_SECONDS = 30;

    /**
     * A conversation that is stopped now.
     *
     * @param reason        {@code silent}: its turn's model call has produced nothing for the threshold;
     *                      {@code usage-limit}: a turn was refused for the usage limit, its request is back at the
     *                      front and its queue is held
     * @param callStartedAt when the silent call began, or when the queue was held
     * @param silentMillis  how long since then
     */
    public record Stalled(String projectId, String chatId, String reason, long callStartedAt, long silentMillis,
                          int queued) { }

    /**
     * What one recovery did.
     *
     * @param outcome {@code recovered} (the turn ended and its prompt is back at the front), {@code not-running} (no
     *                turn was running; the queue was released), {@code not-found}, or {@code not-ended} (the turn did
     *                not end even after its process was killed; its prompt goes back when it does)
     * @param how     {@code cancel} when the cancel ended the turn, {@code kill} when the process had to be killed,
     *                {@code release} when a queue held for the usage limit was released, {@code none} when nothing
     *                was stopped
     * @param queued  the number of prompts waiting in its queue afterwards, the one put back included
     */
    public record Outcome(String projectId, String chatId, String outcome, String how, int queued) { }

    private final Supplier<Map<String, ChatSessionIIAR>> sessions;
    private final LongSupplier clock;
    private final long thresholdMillis;
    private final long autoRecoverMillis;
    private final long stopWaitMillis;
    private ActorRef<StalledTurnRecovery> self;
    private Scheduler scheduler;

    /**
     * @param sessions          the conversations alive now, by actor name ({@code project1/chat-01}); read each time
     * @param clock             "now", in milliseconds since the epoch; a test moves it forward
     * @param thresholdMillis   how long a call may produce nothing before its turn is stalled
     * @param autoRecoverMillis how long a turn may stay stalled before the schedule recovers it; 0 or less: never
     * @param stopWaitMillis    how long the cancel, and then the kill, is given to end the turn
     */
    public StalledTurnRecovery(Supplier<Map<String, ChatSessionIIAR>> sessions, LongSupplier clock,
                               long thresholdMillis, long autoRecoverMillis, long stopWaitMillis) {
        this.sessions = sessions;
        this.clock = clock;
        this.thresholdMillis = thresholdMillis;
        this.autoRecoverMillis = autoRecoverMillis;
        this.stopWaitMillis = stopWaitMillis;
    }

    /** @param self this actor's own reference, which the schedule targets */
    public void bind(ActorRef<StalledTurnRecovery> self) {
        this.self = self;
    }

    /** Starts the schedule, if automatic recovery is on. */
    public void startWatching() {
        if (autoRecoverMillis <= 0) {
            LOG.info("StalledTurnRecovery: automatic recovery is off (chat-ui.stall.auto-recover-minutes <= 0)");
            return;
        }
        scheduler = new Scheduler(1);
        scheduler.scheduleWithFixedDelay("stalled-turns", self, StalledTurnRecovery::tick,
                TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        LOG.info("StalledTurnRecovery: a turn stalled " + autoRecoverMillis / 60_000 + " min is recovered automatically");
    }

    /** Stops the schedule. */
    public void stopWatching() {
        if (scheduler != null) scheduler.close();
    }

    /** @return the threshold, in milliseconds */
    public long thresholdMillis() { return thresholdMillis; }

    /** @return how long a turn stays stalled before it is recovered automatically, in milliseconds; 0 or less: never */
    public long autoRecoverMillis() { return autoRecoverMillis; }

    /**
     * Whether a call that began at {@code callStartedAt} and has produced nothing is stalled at {@code now}.
     *
     * @param callStartedAt the call's start, or 0 when no silent call is in flight
     */
    static boolean isStalled(long callStartedAt, long now, long thresholdMillis) {
        return callStartedAt > 0 && now - callStartedAt >= thresholdMillis;
    }

    /** @return the conversations whose turn is stalled now, in the order of their actor names */
    public List<Stalled> stalled() {
        long now = clock.getAsLong();
        List<Stalled> found = new ArrayList<>();
        for (Map.Entry<String, ChatSessionIIAR> e : new java.util.TreeMap<>(sessions.get()).entrySet()) {
            ActorRef<ChatSession> chat = e.getValue().asChatSessionRef();
            long started;
            long held;
            try {
                started = chat.askNow(ChatSession::silentCallStartedAt).get(5, TimeUnit.SECONDS);
                held = chat.askNow(ChatSession::limitHeldSince).get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                continue;
            }
            String[] id = split(e.getKey());
            if (isStalled(started, now, thresholdMillis)) {
                found.add(new Stalled(id[0], id[1], "silent", started, now - started, queued(chat)));
            } else if (held > 0) {
                found.add(new Stalled(id[0], id[1], "usage-limit", held, now - held, queued(chat)));
            }
        }
        return found;
    }

    /**
     * Brings one conversation back: see the class comment. Returns once its turn has ended, or once the cancel and
     * the kill have each had {@link #stopWaitMillis}.
     *
     * @param chatId the conversation's id, {@code 57} or {@code chat-57}
     */
    public Outcome recover(String projectId, String chatId) {
        String id = chatId.startsWith(ChatUiActorSystem.CHAT_PREFIX)
                ? chatId.substring(ChatUiActorSystem.CHAT_PREFIX.length()) : chatId;
        ChatSessionIIAR iiar = sessions.get().get(ChatUiActorSystem.chatActorName(projectId, id));
        if (iiar == null) return new Outcome(projectId, id, "not-found", "none", 0);
        ActorRef<ChatSession> chat = iiar.asChatSessionRef();
        try {
            ActorRef<PromptQueue> queue = chat.askNow(ChatSession::getPromptQueue).get(5, TimeUnit.SECONDS);
            if (queue != null) queue.ask(q -> { q.hold(); return null; }).get(5, TimeUnit.SECONDS);
            boolean stopping = chat.askNow(ChatSession::stopForRecovery).get(5, TimeUnit.SECONDS);
            if (!stopping) {
                boolean wasHeld = chat.askNow(ChatSession::limitHeldSince).get(5, TimeUnit.SECONDS) > 0;
                chat.askNow(c -> { c.clearLimitHold(); return null; }).get(5, TimeUnit.SECONDS);
                if (queue != null) queue.ask(q -> { q.release(chat); return null; }).get(5, TimeUnit.SECONDS);
                if (wasHeld) {
                    LOG.info("StalledTurnRecovery: " + projectId + "/chat-" + id + " released after a usage-limit refusal");
                    return new Outcome(projectId, id, "recovered", "release", queued(chat));
                }
                return new Outcome(projectId, id, "not-running", "none", queued(chat));
            }
            String how = "cancel";
            if (!idleWithin(chat, stopWaitMillis)) {
                how = "kill";
                chat.askNow(c -> { c.forceStopProvider(); c.abandonCallForRecovery(); return null; })
                        .get(5, TimeUnit.SECONDS);
                if (!idleWithin(chat, stopWaitMillis)) {
                    LOG.warning("StalledTurnRecovery: " + projectId + "/chat-" + id + " did not end after its process"
                            + " was killed; its prompt goes back to the queue when it does");
                    return new Outcome(projectId, id, "not-ended", how, queued(chat));
                }
            }
            // The turn's end has asked the queue to put its prompt back; wait for that, so the count includes it.
            if (queue != null) queue.ask(q -> null).get(5, TimeUnit.SECONDS);
            LOG.info("StalledTurnRecovery: " + projectId + "/chat-" + id + " recovered by " + how);
            return new Outcome(projectId, id, "recovered", how, queued(chat));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "StalledTurnRecovery: could not recover " + projectId + "/chat-" + id, e);
            return new Outcome(projectId, id, "not-ended", "none", queued(chat));
        }
    }

    /** Recovers every conversation whose turn is stalled now, one after another. */
    public List<Outcome> recoverAll() {
        List<Outcome> outcomes = new ArrayList<>();
        for (Stalled s : stalled()) outcomes.add(recover(s.projectId(), s.chatId()));
        return outcomes;
    }

    /**
     * On the schedule: recovers the turns stalled for {@link #autoRecoverMillis} past the threshold. Does nothing
     * when automatic recovery is off.
     */
    public List<Outcome> tick() {
        if (autoRecoverMillis <= 0) return List.of();
        List<Outcome> outcomes = new ArrayList<>();
        for (Stalled s : stalled()) {
            long due = "usage-limit".equals(s.reason()) ? autoRecoverMillis : thresholdMillis + autoRecoverMillis;
            if (s.silentMillis() >= due) {
                LOG.warning("StalledTurnRecovery: " + s.projectId() + "/chat-" + s.chatId() + " has produced nothing for "
                        + s.silentMillis() / 1000 + " s; recovering it automatically");
                outcomes.add(recover(s.projectId(), s.chatId()));
            }
        }
        return outcomes;
    }

    private static boolean idleWithin(ActorRef<ChatSession> chat, long millis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            if (!chat.askNow(ChatSession::isBusy).get(5, TimeUnit.SECONDS)) return true;
            if (System.nanoTime() >= deadline) return false;
            Thread.sleep(50);
        }
    }

    private static int queued(ActorRef<ChatSession> chat) {
        try {
            ActorRef<PromptQueue> queue = chat.askNow(ChatSession::getPromptQueue).get(5, TimeUnit.SECONDS);
            return queue == null ? 0 : queue.ask(PromptQueue::getQueueSize).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return -1;
        }
    }

    /** {@code project1/chat-57} to {@code [project1, 57]}. */
    private static String[] split(String actorName) {
        int slash = actorName.indexOf('/');
        String project = slash < 0 ? "" : actorName.substring(0, slash);
        String chat = slash < 0 ? actorName : actorName.substring(slash + 1);
        if (chat.startsWith(ChatUiActorSystem.CHAT_PREFIX)) chat = chat.substring(ChatUiActorSystem.CHAT_PREFIX.length());
        return new String[] {project, chat};
    }
}
