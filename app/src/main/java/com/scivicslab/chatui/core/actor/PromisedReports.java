package com.scivicslab.chatui.core.actor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reports a conversation has promised and has been registered for with {@code expect_report}, each to
 * be asked for once when its time comes ({@code ReplyDecisionAndPromisedReport_261108_oo01}). Its
 * conversation's {@link Timekeeper} holds one and turns each due promise into a reminder in the
 * conversation's queue.
 *
 * <p>A registration is used once: {@link #due} hands it out and forgets it. Whether to wait again is the
 * asker's decision on the answer, made by registering again, which counts as the next reminder.</p>
 *
 * <p>Not safe to share between threads: its timekeeper owns it, on its own actor thread.</p>
 */
public final class PromisedReports {

    /**
     * One reminder to give.
     *
     * @param requestId the request the report was promised for, or {@code null} for a promise made to the person
     * @param asker     the qualified name of the conversation waiting for the report, or {@code null} for the person
     * @param number    how many times this report has been asked for, this one included, counting from 1
     */
    public record Reminder(String requestId, String asker, int number) {

        /** @return whether the report was promised to the person, so the answer stays on the screen */
        public boolean toScreen() {
            return asker == null;
        }
    }

    private record Promise(String requestId, String asker, long dueAt, int number) {}

    private static final String SCREEN = "(screen)";

    private final Map<String, Promise> promises = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();

    /**
     * Registers a promised report, replacing any registration for the same request.
     *
     * @param requestId the request the report was promised for, or {@code null} for a promise made to the person
     * @param asker     the qualified name of the conversation waiting for it, or {@code null} for the person
     * @param dueAt     when to ask for it, in milliseconds since the epoch
     * @return which reminder this registration will give for the request, counting from 1
     */
    public int expect(String requestId, String asker, long dueAt) {
        String key = requestId == null ? SCREEN : requestId;
        int number = counts.merge(key, 1, Integer::sum);
        promises.put(key, new Promise(requestId, asker == null || requestId == null ? null : asker, dueAt, number));
        return number;
    }

    /**
     * Hands out and forgets the promises whose time has come.
     *
     * @param now the current time, in milliseconds since the epoch
     * @return the reminders to give, in the order they were registered
     */
    public List<Reminder> due(long now) {
        List<Reminder> due = new ArrayList<>();
        for (Iterator<Promise> it = promises.values().iterator(); it.hasNext(); ) {
            Promise p = it.next();
            if (p.dueAt() <= now) {
                due.add(new Reminder(p.requestId(), p.asker(), p.number()));
                it.remove();
            }
        }
        return due;
    }

    /** @return how many registrations are waiting for their time */
    public int pending() {
        return promises.size();
    }
}
