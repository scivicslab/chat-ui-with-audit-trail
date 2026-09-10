package com.scivicslab.chatui.audittrail;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for when an answer is renewed and what is served in the meantime.
 *
 * <p>Exercises the load-bearing path: the watcher must renew an answer no model produced within
 * the minute it may stand, must not renew one a model produced for half an hour, and must keep
 * serving the answer it holds while a renewal is in flight — the dashboard that asks gives up
 * after a second.</p>
 */
@Tag("ActivitySummary_260905_oo01")
class ActivityWatcherTest {

    /** An {@link ActivityWork} that answers with what the test tells it, without a model. */
    static class StubWork extends ActivityWork {
        volatile ActivityAnswer next = new ActivityAnswer("stub", Instant.now(), List.of(), true);
        volatile int computeCalls;

        @Override
        public ActivityAnswer compute() {
            computeCalls++;
            return next;
        }
    }

    @Test
    void isStale_ofAnAnswerNoModelProduced_afterItsMinute_isTrue() {
        ActivityAnswer notFromModel =
                new ActivityAnswer("No conversation recorded yet.", Instant.now(), List.of(), false);

        assertFalse(notFromModel.isStale(Instant.now().plusSeconds(30)));
        assertTrue(notFromModel.isStale(Instant.now().plusSeconds(61)));
    }

    @Test
    void isStale_ofAnAnswerAModelProduced_standsForHalfAnHour() {
        ActivityAnswer fromModel =
                new ActivityAnswer("Refactoring the parallel execution in Turing-workflow.",
                        Instant.now(), List.of(), true);

        assertFalse(fromModel.isStale(Instant.now().plusSeconds(60 * 29)));
        assertTrue(fromModel.isStale(Instant.now().plusSeconds(60 * 31)));
    }

    @Test
    void tick_whenTheHeldAnswerIsStale_replacesIt() {
        ActorSystem system = new ActorSystem("activity-test");
        try {
            StubWork work = new StubWork();
            work.next = new ActivityAnswer("Measuring the queue wait in quarkus-slurm-monitor.",
                    Instant.now(), List.of(), true);
            ActorRef<ActivityWatcher> ref =
                    system.actorOf("activity", new ActivityWatcher(work));
            ref.tell(w -> w.bind(ref, system.getManagedThreadPool())).join();

            // The answer held before anything is worked out is the pending one, and it is stale
            // (no model produced it), so one tick must replace it.
            assertEquals("", ref.ask(ActivityWatcher::current).join().summary());

            ref.tell(ActivityWatcher::tick).join();
            awaitSummary(ref, "Measuring the queue wait in quarkus-slurm-monitor.");

            assertEquals(1, work.computeCalls);
        } finally {
            system.terminate();
        }
    }

    @Test
    void tick_whileTheHeldAnswerStands_asksNothing() {
        ActorSystem system = new ActorSystem("activity-test");
        try {
            StubWork work = new StubWork();
            ActorRef<ActivityWatcher> ref =
                    system.actorOf("activity", new ActivityWatcher(work));
            ref.tell(w -> w.bind(ref, system.getManagedThreadPool())).join();

            ref.tell(ActivityWatcher::tick).join();
            awaitSummary(ref, "stub");
            int afterFirst = work.computeCalls;

            // The answer now held came from a model, so it stands for half an hour: further ticks
            // must not call the model again.
            ref.tell(ActivityWatcher::tick).join();
            ref.tell(ActivityWatcher::tick).join();

            assertEquals(afterFirst, work.computeCalls);
        } finally {
            system.terminate();
        }
    }

    /** Waits for the renewal, which finishes on the pool and comes back through the mailbox. */
    private static void awaitSummary(ActorRef<ActivityWatcher> ref, String expected) {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(ref.ask(ActivityWatcher::current).join().summary())) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(expected, ref.ask(ActivityWatcher::current).join().summary());
    }
}
