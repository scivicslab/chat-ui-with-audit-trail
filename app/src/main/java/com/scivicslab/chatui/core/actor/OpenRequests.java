package com.scivicslab.chatui.core.actor;

import java.util.HashMap;
import java.util.LinkedHashSet;
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
 * to those child requests start. The original request is answered by the first turn that ends with
 * none of its child requests still out. One request gets one reply. After that reply, the original
 * request is kept only as long as it is the last one, so that a turn the provider starts by itself
 * afterwards can still send its answer to the same asker as a follow-up.</p>
 *
 * <p>Not safe to share between threads: one {@link ChatSession} owns one, on its own actor thread.</p>
 */
public final class OpenRequests {

    /** One original request. */
    private static final class Original {
        final String asker;
        final CompletableFuture<TurnReply> done;
        final Set<String> outstanding = new LinkedHashSet<>();
        boolean answered;

        Original(String asker, CompletableFuture<TurnReply> done) {
            this.asker = asker;
            this.done = done;
        }
    }

    private final Map<String, Original> originals = new HashMap<>();
    private final Map<String, String> originalOfChild = new HashMap<>();
    private String last;

    /**
     * Records a request this conversation has received.
     *
     * @param requestId the request's id
     * @param asker     the qualified name of the conversation that sent it
     * @param done      the future the asker waits on; completed when the request is answered
     */
    public void received(String requestId, String asker, CompletableFuture<TurnReply> done) {
        forgetLastIfAnswered();
        originals.put(requestId, new Original(asker, done));
        last = requestId;
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
        Original o = originals.get(original);
        if (o == null || o.answered) return;
        o.outstanding.add(childRequestId);
        originalOfChild.put(childRequestId, original);
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
        if (o == null || o.answered || !o.outstanding.isEmpty()) return null;
        o.answered = true;
        o.done.complete(reply);
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

    private void forgetLastIfAnswered() {
        if (last == null) return;
        Original o = originals.get(last);
        if (o != null && o.answered) originals.remove(last);
    }
}
