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

    /**
     * One registration waiting for its time.
     *
     * @param requestId the request the report was promised for, or {@code null} for a promise made to the person
     * @param asker     who waits for it, or {@code null} for the person
     * @param dueAt     when it will be asked for, in milliseconds since the epoch
     * @param number    which reminder it will give for the request, counting from 1
     */
    public record Pending(String requestId, String asker, long dueAt, int number) {}

    /** @return the registrations waiting for their time, soonest first */
    public List<Pending> snapshot() {
        List<Pending> all = new ArrayList<>();
        for (Promise p : promises.values()) all.add(new Pending(p.requestId(), p.asker(), p.dueAt(), p.number()));
        all.sort((a, b) -> Long.compare(a.dueAt(), b.dueAt()));
        return all;
    }

    /**
     * The registrations waiting and how many times each request has been asked for, as the timekeeper records them
     * ({@code EachActorRecordsItsOwnState_261118_oo01}). Times are absolute, so a restart keeps them.
     */
    public org.json.JSONObject toJson() {
        org.json.JSONArray list = new org.json.JSONArray();
        for (Promise p : promises.values()) {
            list.put(new org.json.JSONObject()
                    .put("requestId", p.requestId() == null ? org.json.JSONObject.NULL : p.requestId())
                    .put("asker", p.asker() == null ? org.json.JSONObject.NULL : p.asker())
                    .put("dueAt", p.dueAt()).put("number", p.number()));
        }
        return new org.json.JSONObject().put("promises", list).put("counts", new org.json.JSONObject(counts));
    }

    /** Puts back what {@link #toJson} gave, replacing what is held. */
    public void load(org.json.JSONObject saved) {
        promises.clear();
        counts.clear();
        org.json.JSONObject savedCounts = saved.optJSONObject("counts");
        if (savedCounts != null) for (String k : savedCounts.keySet()) counts.put(k, savedCounts.optInt(k));
        org.json.JSONArray list = saved.optJSONArray("promises");
        for (int i = 0; list != null && i < list.length(); i++) {
            org.json.JSONObject o = list.optJSONObject(i);
            if (o == null || !o.has("dueAt")) continue;
            String requestId = o.isNull("requestId") ? null : o.optString("requestId", null);
            String asker = o.isNull("asker") ? null : o.optString("asker", null);
            promises.put(requestId == null ? SCREEN : requestId,
                    new Promise(requestId, asker, o.optLong("dueAt"), Math.max(1, o.optInt("number", 1))));
        }
    }

    /** @return how many registrations are waiting for their time */
    public int pending() {
        return promises.size();
    }
}
