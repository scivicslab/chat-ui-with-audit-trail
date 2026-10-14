package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The order of one project's jobs, without any actor ({@code ProjectJobQueue_261014_oo01}). */
@Tag("ProjectJobQueue_261014_oo01")
@DisplayName("ジョブの待ち行列")
class JobQueueTest {

    private static JobQueue.Entry entry(String jobId) {
        return new JobQueue.Entry(jobId, "w", "steps: []", Map.of());
    }

    private static String idOf(Optional<JobQueue.Entry> e) {
        return e.map(JobQueue.Entry::jobId).orElse(null);
    }

    @Test
    @DisplayName("空のときに足したジョブはすぐ実行し、実行中があれば待たせる")
    void offer_startsWhenIdle_waitsWhenBusy() {
        JobQueue q = new JobQueue();

        assertEquals("job-01", idOf(q.offer(entry("job-01"))));
        assertNull(idOf(q.offer(entry("job-02"))), "job-01 is running");
        assertNull(idOf(q.offer(entry("job-03"))));

        assertEquals("job-01", q.running());
        assertEquals(List.of("job-02", "job-03"), q.waiting());
    }

    @Test
    @DisplayName("終わるたびに足した順に次を返し、空になれば何も返さない")
    void finished_handsOutInSubmissionOrder() {
        JobQueue q = new JobQueue();
        q.offer(entry("job-01"));
        q.offer(entry("job-02"));
        q.offer(entry("job-03"));

        assertEquals("job-02", idOf(q.finished("job-01")));
        assertEquals("job-03", idOf(q.finished("job-02")));
        assertNull(idOf(q.finished("job-03")));
        assertNull(q.running());
        assertEquals("job-04", idOf(q.offer(entry("job-04"))), "idle again, so it starts at once");
    }

    @Test
    @DisplayName("実行中でないジョブの終わりは何も変えない")
    void finished_ofAJobThatIsNotRunning_changesNothing() {
        JobQueue q = new JobQueue();
        q.offer(entry("job-01"));
        q.offer(entry("job-02"));

        assertNull(idOf(q.finished("job-02")), "job-02 is waiting, not running");
        assertNull(idOf(q.finished("job-99")));
        assertEquals("job-01", q.running());
        assertEquals(List.of("job-02"), q.waiting());
    }

    @Test
    @DisplayName("待っているジョブは外せ、実行中のジョブは外せない")
    void cancel_removesAWaitingJobOnly() {
        JobQueue q = new JobQueue();
        q.offer(entry("job-01"));
        q.offer(entry("job-02"));
        q.offer(entry("job-03"));

        assertTrue(q.cancel("job-02"));
        assertFalse(q.cancel("job-01"), "running, not waiting");
        assertFalse(q.cancel("job-02"), "already gone");
        assertEquals(List.of("job-03"), q.waiting());
        assertEquals("job-03", idOf(q.finished("job-01")), "the cancelled one is skipped");
    }
}
