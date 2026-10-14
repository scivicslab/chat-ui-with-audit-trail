package com.scivicslab.chatui.core.actor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The order in which one project's jobs run: one job at a time, the rest waiting in the order they
 * were submitted ({@code ProjectJobQueue_261014_oo01}).
 *
 * <p>Held by the project's child actor {@code <projectId>/job-queue}. It only answers which job
 * runs next; starting a job, which needs the actor system, the watchdog and the job's log, stays
 * with {@link Project}. Nothing here knows about actors, so it is tested as a plain class.</p>
 */
public class JobQueue {

    /**
     * One job waiting to run, with everything {@link Project} needs to start it.
     *
     * @param jobId      {@code job-NN}, taken when the job was submitted
     * @param workflow   the workflow's name, as the catalog lists it
     * @param yaml       its text
     * @param parameters the values its {@code ${key}} stand for; never null
     */
    public record Entry(String jobId, String workflow, String yaml, Map<String, String> parameters) {}

    private final Deque<Entry> waiting = new ArrayDeque<>();
    private String running;

    /**
     * Adds a job to the end of the line.
     *
     * @return the job to start now: the head of the line when nothing is running, else empty
     */
    public Optional<Entry> offer(Entry entry) {
        waiting.addLast(entry);
        return running == null ? next() : Optional.empty();
    }

    /**
     * Records that the running job ended.
     *
     * @param jobId the job that ended; anything other than the running job changes nothing
     * @return the next job to start, or empty when none is waiting
     */
    public Optional<Entry> finished(String jobId) {
        if (running == null || !running.equals(jobId)) return Optional.empty();
        running = null;
        return next();
    }

    /**
     * Takes a waiting job out of the line. The running job is not affected.
     *
     * @return whether the job was waiting
     */
    public boolean cancel(String jobId) {
        return waiting.removeIf(e -> e.jobId().equals(jobId));
    }

    /** @return the ids of the waiting jobs, the next to run first */
    public List<String> waiting() {
        List<String> ids = new ArrayList<>();
        for (Entry e : waiting) ids.add(e.jobId());
        return ids;
    }

    /** @return the id of the running job, or {@code null} */
    public String running() {
        return running;
    }

    private Optional<Entry> next() {
        Entry head = waiting.pollFirst();
        if (head == null) return Optional.empty();
        running = head.jobId();
        return Optional.of(head);
    }
}
