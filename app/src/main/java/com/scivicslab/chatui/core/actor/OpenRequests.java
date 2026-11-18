package com.scivicslab.chatui.core.actor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code ask_chat} requests a conversation has received and not yet answered, and the requests it
 * has sent for each of them, so that the answer goes back to the asker only when the work is done
 * ({@code ReportReachesTheAskerAfterDelegation_261107_oo01}).
 *
 * <p>A request a conversation receives is an <em>original request</em>. Requests its turns send
 * while working on it are its <em>child requests</em>; so are requests sent by turns that the replies
 * to those child requests start. The original request is answered when the first turn that ends with
 * none of its child requests still out ends. One request gets one reply, and it carries the answer of
 * every turn that worked for the request, each under its turn's id: a report written in a turn that
 * ended while a child request was still out is not dropped for the answer of the turn that saw the
 * last reply come back, which may only say that the report was written. After that reply, the original
 * request is kept only as long as it is the last one, so that a turn the provider starts by itself
 * afterwards can still send its answer to the same asker as a follow-up.</p>
 *
 * <p>Not safe to share between threads: one {@link ChatSession} owns one, on its own actor thread.</p>
 */
public final class OpenRequests {

    /** One original request. */
    private static final class Original {
        final String asker;
        final boolean urgent;
        final RemoteLink link;
        final CompletableFuture<TurnReply> done;
        final Set<String> outstanding = new LinkedHashSet<>();
        /** The answers of the turns that ended while a child request was still out, oldest first. */
        final List<TurnReply> earlier = new ArrayList<>();
        boolean answered;

        Original(String asker, boolean urgent, RemoteLink link, CompletableFuture<TurnReply> done) {
            this.asker = asker;
            this.urgent = urgent;
            this.link = link;
            this.done = done;
        }
    }

    /**
     * A child request still out: its id, the conversation it went to, and when it was sent
     * ({@code TimekeeperPausesATurnForAnUrgentRequest_261107_oo01}).
     */
    public record Outstanding(String requestId, String target, long sentAt) {}

    private final Map<String, Original> originals = new HashMap<>();
    private final Map<String, String> originalOfChild = new HashMap<>();
    /**
     * The original request each child request was sent for, kept after the reply came back (unlike
     * {@link #originalOfChild}), so that a child request can be reopened when its reply promised a report
     * ({@code ReplyDecisionAndPromisedReport_261108_oo01}).
     */
    private final Map<String, String> originalOfSent = new HashMap<>();
    private final Map<String, Outstanding> outstandingChildren = new HashMap<>();
    private String last;

    /**
     * Records a request this conversation has received.
     *
     * @param requestId the request's id
     * @param asker     the qualified name of the conversation that sent it
     * @param done      the future the asker waits on; completed when the request is answered
     */
    public void received(String requestId, String asker, CompletableFuture<TurnReply> done) {
        received(requestId, asker, done, false);
    }

    /** @param urgent whether the request was urgent, so that its reply goes ahead in the asker's queue as it did */
    public void received(String requestId, String asker, CompletableFuture<TurnReply> done, boolean urgent) {
        forgetLastIfAnswered();
        originals.put(requestId, new Original(asker, urgent, null, done));
        last = requestId;
    }

    // ── As the conversation records it (EachActorRecordsItsOwnState_261118_oo01) ─────────────────────

    /** Makes the future a request put back after a restart is answered through: who asked, and how. */
    public interface Answering {
        CompletableFuture<TurnReply> doneFor(String requestId, String asker, boolean urgent, RemoteLink link);
    }

    /**
     * The way back to a sender on another chat-ui instance ({@code ConversationSquadsAcrossInstances_261115_oo01}):
     * the node it is on and the request id it gave, which this instance keeps the request under with the node in
     * front. Part of the received request's own state, recorded with it, so its reply survives a restart.
     */
    public record RemoteLink(String node, String requestId) {}

    /** @param link the way back to a sender on another instance, or {@code null} for a sender on this one */
    public void received(String requestId, String asker, CompletableFuture<TurnReply> done, boolean urgent, RemoteLink link) {
        forgetLastIfAnswered();
        originals.put(requestId, new Original(asker, urgent, link, done));
        last = requestId;
    }

    /**
     * Everything held, as the conversation records it: the requests received (who asked, whether urgent, whether
     * answered, the requests still out for each, the answers of earlier turns) and the requests sent and not yet
     * replied to (where they went, when, and for which received request). Times are absolute.
     */
    public org.json.JSONObject toJson() {
        org.json.JSONArray received = new org.json.JSONArray();
        originals.forEach((id, o) -> {
            org.json.JSONArray earlier = new org.json.JSONArray();
            for (TurnReply r : o.earlier) {
                earlier.put(new org.json.JSONObject().put("reply", r.reply() == null ? org.json.JSONObject.NULL : r.reply())
                        .put("ending", r.ending() == null ? org.json.JSONObject.NULL : r.ending())
                        .put("turnId", r.turnId() == null ? org.json.JSONObject.NULL : r.turnId()));
            }
            received.put(new org.json.JSONObject().put("id", id)
                    .put("asker", o.asker == null ? org.json.JSONObject.NULL : o.asker)
                    .put("urgent", o.urgent).put("answered", o.answered)
                    .put("link", o.link == null ? org.json.JSONObject.NULL
                            : new org.json.JSONObject().put("node", o.link.node()).put("requestId", o.link.requestId()))
                    .put("outstanding", new org.json.JSONArray(o.outstanding)).put("earlier", earlier));
        });
        org.json.JSONArray sent = new org.json.JSONArray();
        for (Outstanding s : outstandingChildren()) {
            sent.put(new org.json.JSONObject().put("requestId", s.requestId())
                    .put("target", s.target() == null ? org.json.JSONObject.NULL : s.target()).put("sentAt", s.sentAt()));
        }
        return new org.json.JSONObject().put("received", received).put("sent", sent)
                .put("originalOfChild", new org.json.JSONObject(originalOfChild))
                .put("originalOfSent", new org.json.JSONObject(originalOfSent))
                .put("last", last == null ? org.json.JSONObject.NULL : last);
    }

    /**
     * Puts back what {@link #toJson} gave, after a restart. A received request not yet answered gets a new future from
     * {@code answering}, which is how its answer reaches the asker; one already answered gets a completed one.
     */
    public void load(org.json.JSONObject saved, Answering answering) {
        originals.clear();
        originalOfChild.clear();
        originalOfSent.clear();
        outstandingChildren.clear();
        org.json.JSONArray received = saved.optJSONArray("received");
        for (int i = 0; received != null && i < received.length(); i++) {
            org.json.JSONObject r = received.optJSONObject(i);
            if (r == null || r.optString("id", "").isBlank()) continue;
            String id = r.getString("id");
            String asker = r.isNull("asker") ? null : r.optString("asker", null);
            boolean urgent = r.optBoolean("urgent", false);
            boolean answered = r.optBoolean("answered", false);
            org.json.JSONObject l = r.optJSONObject("link");
            RemoteLink link = l == null ? null : new RemoteLink(l.getString("node"), l.getString("requestId"));
            CompletableFuture<TurnReply> done = answered
                    ? CompletableFuture.completedFuture(TurnReply.NONE) : answering.doneFor(id, asker, urgent, link);
            Original o = new Original(asker, urgent, link, done);
            o.answered = answered;
            org.json.JSONArray out = r.optJSONArray("outstanding");
            for (int k = 0; out != null && k < out.length(); k++) o.outstanding.add(out.getString(k));
            org.json.JSONArray earlier = r.optJSONArray("earlier");
            for (int k = 0; earlier != null && k < earlier.length(); k++) {
                org.json.JSONObject e = earlier.getJSONObject(k);
                o.earlier.add(new TurnReply(e.isNull("reply") ? null : e.optString("reply"),
                        e.isNull("ending") ? null : e.optString("ending"), e.isNull("turnId") ? null : e.optString("turnId")));
            }
            originals.put(id, o);
        }
        org.json.JSONArray sent = saved.optJSONArray("sent");
        for (int i = 0; sent != null && i < sent.length(); i++) {
            org.json.JSONObject s = sent.optJSONObject(i);
            if (s == null || s.optString("requestId", "").isBlank()) continue;
            outstandingChildren.put(s.getString("requestId"), new Outstanding(s.getString("requestId"),
                    s.isNull("target") ? null : s.optString("target", null), s.optLong("sentAt")));
        }
        copy(saved.optJSONObject("originalOfChild"), originalOfChild);
        copy(saved.optJSONObject("originalOfSent"), originalOfSent);
        last = saved.isNull("last") ? null : saved.optString("last", null);
    }

    private static void copy(org.json.JSONObject from, Map<String, String> to) {
        if (from != null) for (String k : from.keySet()) to.put(k, from.getString(k));
    }

    /**
     * Records that a reply to a child request has arrived, and says which original request the turn
     * it starts works for.
     *
     * @param childRequestId the id of the request the reply answers
     * @return the original request's id, or {@code null} when the reply answers a request that was
     *         not sent for any original request still open
     */
    public String replyArrived(String childRequestId) {
        outstandingChildren.remove(childRequestId);
        String original = originalOfChild.remove(childRequestId);
        if (original == null) return null;
        Original o = originals.get(original);
        if (o == null) return null;
        o.outstanding.remove(childRequestId);
        return original;
    }

    /**
     * Records a child request sent for an original request.
     *
     * @param original       the original request's id
     * @param childRequestId the id of the request sent
     */
    public void childSent(String original, String childRequestId) {
        childSent(original, childRequestId, null, System.currentTimeMillis());
    }

    /**
     * As {@link #childSent(String, String)}, keeping where the request went and when.
     *
     * @param target the qualified name of the conversation the request went to
     * @param sentAt when it was sent, in milliseconds since the epoch
     */
    public void childSent(String original, String childRequestId, String target, long sentAt) {
        Original o = originals.get(original);
        if (o == null || o.answered) return;
        o.outstanding.add(childRequestId);
        originalOfChild.put(childRequestId, original);
        originalOfSent.put(childRequestId, original);
        outstandingChildren.put(childRequestId, new Outstanding(childRequestId, target, sentAt));
    }

    /**
     * Records a request sent by a turn that works for no received request (one the person's prompt, or a
     * reminder, started). No request waits for it, so it holds nothing open; it is counted as out only so that
     * the timekeeper's child-reply watch covers it. Its reply, when it comes, closes it through
     * {@link #replyArrived} like any other.
     *
     * @param childRequestId the id of the request sent
     * @param target         the qualified name of the conversation it went to
     * @param sentAt         when it was sent, in milliseconds since the epoch
     */
    public void sentWithoutAnOriginal(String childRequestId, String target, long sentAt) {
        outstandingChildren.put(childRequestId, new Outstanding(childRequestId, target, sentAt));
    }

    /** What {@link #reopen} did, or why it did not. */
    public enum Reopening {
        /** The child request counts as out again; its original request waits for the report. */
        REOPENED,
        /** The original request was already answered: nothing is held open, the report arrives as a message of its own. */
        ALREADY_ANSWERED,
        /** The request was not sent for a request this conversation received. */
        NOT_SENT_FOR_A_REQUEST,
        /** The child request has not had its reply yet, so there is nothing to reopen. */
        STILL_OUT
    }

    /**
     * Counts a child request whose reply has come back as not yet returned again, because the reply said a
     * report would follow. The original request it was sent for then stays open until the report arrives
     * (through {@link #replyArrived}, like any reply), and the no-reply timer counts from {@code now}
     * ({@code ReplyDecisionAndPromisedReport_261108_oo01}).
     *
     * @param childRequestId the id of the request sent
     * @param target         the qualified name of the conversation the request went to
     * @param now            the time of the reopening, in milliseconds since the epoch
     */
    public Reopening reopen(String childRequestId, String target, long now) {
        if (outstandingChildren.containsKey(childRequestId)) return Reopening.STILL_OUT;
        String original = originalOfSent.get(childRequestId);
        if (original == null) return Reopening.NOT_SENT_FOR_A_REQUEST;
        Original o = originals.get(original);
        if (o == null || o.answered) return Reopening.ALREADY_ANSWERED;
        childSent(original, childRequestId, target, now);
        return Reopening.REOPENED;
    }

    /**
     * Gives up on a child request still counted as out, so that its original request no longer waits for it
     * (for example a reopened one whose report is no longer wanted).
     *
     * @return whether the child request was out
     */
    public boolean close(String childRequestId) {
        if (!outstandingChildren.containsKey(childRequestId)) return false;
        replyArrived(childRequestId);
        return true;
    }

    /** @return the child requests still out, oldest first */
    public List<Outstanding> outstandingChildren() {
        List<Outstanding> all = new ArrayList<>(outstandingChildren.values());
        all.sort((a, b) -> Long.compare(a.sentAt(), b.sentAt()));
        return all;
    }

    /**
     * @param original the original request's id
     * @return whether the request is still open (received, and either not yet answered or the last one)
     */
    public boolean isOpen(String original) {
        return original != null && originals.containsKey(original);
    }

    /**
     * Called when a turn working for an original request ends. Answers the request if none of its
     * child requests is still out and it has not been answered yet.
     *
     * @param original the original request's id
     * @param reply    the turn's answer
     * @return the future that was completed, or {@code null} when the request was not answered now
     */
    public CompletableFuture<TurnReply> turnEnded(String original, TurnReply reply) {
        Original o = originals.get(original);
        if (o == null || o.answered) return null;
        if (!o.outstanding.isEmpty()) {
            if (reply != null && reply.reply() != null && !reply.reply().isBlank()) o.earlier.add(reply);
            return null;
        }
        o.answered = true;
        o.done.complete(withEarlierAnswers(o.earlier, reply));
        if (!original.equals(last)) originals.remove(original);
        return o.done;
    }

    /**
     * @param original the original request's id
     * @return the future the asker of the request waits on, or {@code null} if the request is not open
     */
    public CompletableFuture<TurnReply> doneOf(String original) {
        Original o = originals.get(original);
        return o == null ? null : o.done;
    }

    /**
     * @param original the original request's id
     * @return the qualified name of the conversation that sent the request, or {@code null} if it is not open
     */
    public String askerOf(String original) {
        Original o = originals.get(original);
        return o == null ? null : o.asker;
    }

    /** @return the id of the last original request received, or {@code null} */
    public String last() {
        return last;
    }

    /**
     * @param original the original request's id
     * @return the asker's qualified name when the request has been answered, else {@code null}
     */
    public String askerIfAnswered(String original) {
        Original o = originals.get(original);
        return o != null && o.answered ? o.asker : null;
    }

    /** @return how many child requests of the original request are still out, or -1 if it is not open */
    public int outstanding(String original) {
        Original o = originals.get(original);
        return o == null ? -1 : o.outstanding.size();
    }

    /**
     * The reply to send: the last turn's reply alone when no earlier turn left an answer, else every
     * answer under its turn's id, oldest first, with the last turn's ending line and id.
     */
    static TurnReply withEarlierAnswers(List<TurnReply> earlier, TurnReply last) {
        if (earlier.isEmpty()) return last;
        StringBuilder text = new StringBuilder();
        for (TurnReply r : earlier) appendAnswer(text, r);
        if (last.reply() != null && !last.reply().isBlank()) appendAnswer(text, last);
        return new TurnReply(text.toString(), last.ending(), last.turnId());
    }

    private static void appendAnswer(StringBuilder text, TurnReply r) {
        if (!text.isEmpty()) text.append("\n\n");
        text.append("[answer of turn ").append(r.turnId()).append("]\n").append(r.reply());
    }

    private void forgetLastIfAnswered() {
        if (last == null) return;
        Original o = originals.get(last);
        if (o != null && o.answered) originals.remove(last);
    }
}
