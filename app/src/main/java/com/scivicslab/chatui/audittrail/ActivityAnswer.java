package com.scivicslab.chatui.audittrail;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One worked-out answer about what this instance is doing, and the moment it was worked out
 * ({@code ActivitySummary_260905_oo01}).
 *
 * <p>Immutable, so that {@link ActivityWatcher} can hand the same instance to whoever asks without
 * either side having to copy it.</p>
 *
 * @param summary   the line for the Instances table
 * @param asOf      when this answer was worked out
 * @param parts     the breakdown, one entry per project
 * @param fromModel whether a model produced the summary, which decides how long it stands
 */
public record ActivityAnswer(String summary, Instant asOf, List<Map<String, String>> parts,
                             boolean fromModel) {

    /**
     * How long an answer stands before it is worked out again.
     *
     * <p>Every answer costs one call to the model per project, and this is drawn on a screen that
     * lists every running tool. What it says — which piece of work a conversation is on — does not
     * turn over in minutes.</p>
     */
    public static final Duration MAX_AGE = Duration.ofMinutes(30);

    /**
     * How long an answer that no model produced stands.
     *
     * <p>Much shorter, because these are the two answers that are about to stop being true. An
     * instance is asked as soon as it is READY, before anyone has said anything to it, and the
     * answer worked out then — "no conversation recorded yet" — would otherwise be repeated for
     * half an hour after the work began. The same goes for a failure: the model being unreachable
     * now says nothing about the next half hour. Neither costs a model call to work out again.</p>
     */
    public static final Duration RETRY_AGE = Duration.ofMinutes(1);

    /**
     * The answer held before anything has been worked out.
     *
     * <p>Dated {@link Instant#EPOCH} so that {@link #isStale} is true from the first moment and the
     * first tick works one out. Dating it {@code now} would hold this empty answer for the whole of
     * {@link #RETRY_AGE} after startup — the case the code this replaced covered by holding
     * {@code null} and calling a missing answer stale.</p>
     */
    public static ActivityAnswer pending() {
        return new ActivityAnswer("", Instant.EPOCH, List.of(), false);
    }

    /** @return whether nothing has been worked out yet */
    public boolean isPending() {
        return Instant.EPOCH.equals(asOf);
    }

    /**
     * @param now the moment to judge against
     * @return whether this answer has stood longer than it may
     */
    public boolean isStale(Instant now) {
        return isPending()
                || Duration.between(asOf, now).compareTo(fromModel ? MAX_AGE : RETRY_AGE) > 0;
    }
}
