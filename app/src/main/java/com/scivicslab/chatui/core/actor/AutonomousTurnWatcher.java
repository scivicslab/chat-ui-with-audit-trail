package com.scivicslab.chatui.core.actor;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;

/**
 * Asks every conversation, every {@link #TICK_SECONDS} seconds, to surface a turn its provider produced with no
 * prompt — the Claude Code CLI starts one by itself when a background job the model started finishes.
 * {@link ChatSession#pollAutonomousActivity} does the work on the conversation's own actor thread: when the
 * conversation is idle and the provider holds such output, it streams the turn to the screen and records it in the
 * history; otherwise it returns at once.
 *
 * <p>The schedule targets this actor rather than each conversation, because {@link Scheduler} waits for its target
 * to take the message: a conversation whose actor thread is busy would hold up the check of every other one. This
 * actor hands each conversation a {@code tell}, which does not wait.</p>
 */
public class AutonomousTurnWatcher {

    private static final Logger LOG = Logger.getLogger(AutonomousTurnWatcher.class.getName());

    /** How often the conversations are asked. Short enough that a finished background job shows within seconds. */
    static final long TICK_SECONDS = 2;

    private final Supplier<Collection<ChatSessionIIAR>> sessions;
    private ActorRef<AutonomousTurnWatcher> self;
    private Scheduler scheduler;

    /** @param sessions the conversations alive now; read at every tick, so added and deleted ones are followed */
    public AutonomousTurnWatcher(Supplier<Collection<ChatSessionIIAR>> sessions) {
        this.sessions = sessions;
    }

    /** @param self this actor's own reference, which the schedule targets */
    public void bind(ActorRef<AutonomousTurnWatcher> self) {
        this.self = self;
    }

    /** Starts the schedule. */
    public void startWatching() {
        scheduler = new Scheduler(1);
        scheduler.scheduleWithFixedDelay("autonomous-turns", self, AutonomousTurnWatcher::tick,
                TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        LOG.info("AutonomousTurnWatcher started (every " + TICK_SECONDS + "s)");
    }

    /** Stops the schedule. Called when the actor system is torn down. */
    public void stopWatching() {
        if (scheduler != null) scheduler.close();
    }

    /** On the mailbox, and cheap: one {@code tell} per conversation. */
    void tick() {
        for (ChatSessionIIAR session : sessions.get()) {
            ActorRef<ChatSession> ref = session.asChatSessionRef();
            ref.tell(c -> c.pollAutonomousActivity(ref));
        }
    }
}
