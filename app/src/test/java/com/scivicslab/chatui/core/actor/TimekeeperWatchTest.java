package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * What the timekeeper view shows is {@link Timekeeper#summarize}: a plain function of the state the timekeeper
 * reads (its limits, the requests out, the promised reports, the front of the queue), so it is tested without
 * actors.
 */
class TimekeeperWatchTest {

    private static final long MIN = 60_000L;
    private static final long NOW = 1_000_000_000_000L;

    private static Timekeeper.Watch watch(boolean running, Long runningSince, Long urgentQueuedAt,
                                          List<OpenRequests.Outstanding> out, Set<String> warned,
                                          List<PromisedReports.Pending> promised) {
        return Timekeeper.summarize("project1/chat-01", 10 * MIN, 5 * MIN, running, runningSince, urgentQueuedAt,
                out, warned, promised, NOW);
    }

    @Test
    void limitsAreReportedInMinutes() {
        Timekeeper.Watch w = watch(false, null, null, List.of(), Set.of(), List.of());
        assertEquals("project1/chat-01", w.conversation());
        assertEquals(10, w.urgentWaitMinutes());
        assertEquals(5, w.childReplyWarnMinutes());
        assertTrue(w.sent().isEmpty());
        assertTrue(w.promised().isEmpty());
        assertFalse(w.urgentWaiting());
    }

    @Test
    void aRequestOutShowsItsAgeAndWhetherTheNoticeWasSent() {
        var young = new OpenRequests.Outstanding("project1/chat-01:s1:t1:r1", "project1/chat-02", NOW - 2 * MIN);
        var old = new OpenRequests.Outstanding("project1/chat-01:s1:t1:r2", "project1/chat-03", NOW - 7 * MIN);
        Timekeeper.Watch w = watch(false, null, null, List.of(old, young), Set.of(old.requestId()), List.of());

        assertEquals(2, w.sent().size());
        Timekeeper.Watch.Sent a = w.sent().get(0);
        assertEquals(old.requestId(), a.requestId());
        assertEquals("project1/chat-03", a.target());
        assertEquals(7 * MIN, a.outMillis());
        assertTrue(a.noticeSent(), "the notice for the old request went out");
        assertEquals(-2 * MIN, a.noticeDueInMillis(), "it was due 2 minutes ago");

        Timekeeper.Watch.Sent b = w.sent().get(1);
        assertEquals(2 * MIN, b.outMillis());
        assertFalse(b.noticeSent());
        assertEquals(3 * MIN, b.noticeDueInMillis(), "the notice is due in 3 minutes");
    }

    @Test
    void aRequestOutWithoutATargetIsShownWithoutOne() {
        var o = new OpenRequests.Outstanding("r", null, NOW - MIN);
        assertNull(watch(false, null, null, List.of(o), Set.of(), List.of()).sent().get(0).target());
    }

    @Test
    void aPromisedReportShowsItsRequestWhenItIsDueAndWhichReminderItIs() {
        var forRequest = new PromisedReports.Pending("project1/chat-01:s1:t1:r1", "project1/chat-02", NOW + 4 * MIN, 2);
        var forPerson = new PromisedReports.Pending(null, null, NOW - MIN, 1);
        Timekeeper.Watch w = watch(false, null, null, List.of(), Set.of(), List.of(forPerson, forRequest));

        assertEquals(2, w.promised().size());
        Timekeeper.Watch.Promised p = w.promised().get(0);
        assertNull(p.requestId(), "a promise to the person has no request id");
        assertNull(p.asker());
        assertEquals(-MIN, p.dueInMillis(), "it is already due; the next check reminds");
        assertEquals(1, p.number());

        Timekeeper.Watch.Promised q = w.promised().get(1);
        assertEquals("project1/chat-01:s1:t1:r1", q.requestId());
        assertEquals("project1/chat-02", q.asker());
        assertEquals(4 * MIN, q.dueInMillis());
        assertEquals(2, q.number());
    }

    @Test
    void anUrgentItemWaitingWhileATurnRunsShowsHowLongUntilThePause() {
        Timekeeper.Watch w = watch(true, NOW - 30 * MIN, NOW - 3 * MIN, List.of(), Set.of(), List.of());
        assertTrue(w.running());
        assertEquals(NOW - 30 * MIN, w.runningSince());
        assertTrue(w.urgentWaiting());
        assertEquals(3 * MIN, w.urgentWaitingMillis());
        assertEquals(7 * MIN, w.pauseInMillis(), "10 minutes allowed, 3 have gone");
    }

    @Test
    void anUrgentItemPastTheLimitIsPausedAtTheNextCheck() {
        Timekeeper.Watch w = watch(true, NOW - 30 * MIN, NOW - 12 * MIN, List.of(), Set.of(), List.of());
        assertEquals(-2 * MIN, w.pauseInMillis(), "negative: past the limit, the next check pauses the turn");
    }

    @Test
    void anUrgentItemInAnIdleConversationIsNotWaitingToPauseAnything() {
        Timekeeper.Watch w = watch(false, null, NOW - 3 * MIN, List.of(), Set.of(), List.of());
        assertTrue(w.urgentWaiting());
        assertNull(w.pauseInMillis(), "no turn runs, so nothing would be paused; the item is answered next");
    }

    @Test
    void noUrgentItemMeansNoWaitingAndNoPause() {
        Timekeeper.Watch w = watch(true, NOW - MIN, null, List.of(), Set.of(), List.of());
        assertFalse(w.urgentWaiting());
        assertNull(w.urgentWaitingMillis());
        assertNull(w.pauseInMillis());
    }

    @Test
    void theRegisteredReminderNumberComesFromThePromisedReports() {
        PromisedReports p = new PromisedReports();
        p.expect("r", "project1/chat-02", NOW + MIN);
        p.expect("r", "project1/chat-02", NOW + 2 * MIN);
        assertEquals(2, p.snapshot().get(0).number(), "registering again counts as the next reminder");
    }
}
