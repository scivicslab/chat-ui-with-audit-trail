package com.scivicslab.chatui.core.actor;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;

/**
 * Has every conversation's {@link Timekeeper} check, every {@link #TICK_SECONDS} seconds
 * ({@code TimekeeperPausesATurnForAnUrgentRequest_261107_oo01}). One schedule for all of them, as
 * {@link AutonomousTurnWatcher} has, rather than one thread per conversation; it hands each timekeeper a
 * {@code tell}, which does not wait.
 */
public class TimekeeperTicker {

    private static final Logger LOG = Logger.getLogger(TimekeeperTicker.class.getName());

    /** How often the timekeepers check. Fine-grained enough for limits counted in minutes. */
    static final long TICK_SECONDS = 30;

    private final Supplier<Collection<ActorRef<Timekeeper>>> timekeepers;
    private ActorRef<TimekeeperTicker> self;
    private Scheduler scheduler;

    /** @param timekeepers the timekeepers alive now; read at every tick, so added and deleted ones are followed */
    public TimekeeperTicker(Supplier<Collection<ActorRef<Timekeeper>>> timekeepers) {
        this.timekeepers = timekeepers;
    }

    /** @param self this actor's own reference, which the schedule targets */
    public void bind(ActorRef<TimekeeperTicker> self) {
        this.self = self;
    }

    /** Starts the schedule. */
    public void start() {
        scheduler = new Scheduler(1);
        scheduler.scheduleWithFixedDelay("timekeepers", self, TimekeeperTicker::tick,
                TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        LOG.info("TimekeeperTicker started (every " + TICK_SECONDS + "s)");
    }

    /** Stops the schedule. Called when the actor system is torn down. */
    public void stop() {
        if (scheduler != null) scheduler.close();
    }

    void tick() {
        long now = System.currentTimeMillis();
        for (ActorRef<Timekeeper> t : timekeepers.get()) t.tell(k -> k.tick(now));
    }
}
