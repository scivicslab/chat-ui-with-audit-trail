package com.scivicslab.chatui.agent;

/**
 * Pure helpers for keeping the LLM request within a token budget (s_budget).
 *
 * <p>Trimming applies ONLY to what the model receives. The complete I/O log (s_iolog) records the
 * full, untrimmed content separately, so nothing is lost from the record.</p>
 *
 * <p>Token counts are a deliberately conservative char-based estimate (no tokenizer): over-estimating
 * tokens makes us trim a little earlier, which keeps us safely under the real limit.</p>
 */
public final class ContextBudget {

    private ContextBudget() {}

    /**
     * Chars per token for the estimate. 2.0 stays conservative even for Japanese (which is denser in
     * tokens than English ~4 chars/token), so the estimate never under-counts and the request stays
     * under the model's real limit even at a large context window (lower = trims earlier). English
     * conversations therefore trim a little earlier than strictly necessary — an acceptable trade for
     * not overflowing the model on Japanese-heavy context.
     */
    static final double CHARS_PER_TOKEN = 2.0;
    /** Per-message overhead (role + formatting) added to each message's content estimate. */
    static final int PER_MESSAGE_OVERHEAD = 4;

    /**
     * Observation size used when the workflow's {@code runTool} action names none
     * ({@code TurnResourceLimits_260830_oo01}). Not the limit itself — the limit is whatever the
     * workflow says, bounded by the ceiling the model's context length implies.
     */
    public static final int OBS_THRESHOLD = 8000;
    /** Fraction of a truncated observation kept from its head; the rest of the kept text is its tail. */
    private static final double HEAD_SHARE = 0.75;
    /** Characters kept from the head of an observation truncated at {@link #OBS_THRESHOLD}. */
    public static final int OBS_HEAD = (int) (OBS_THRESHOLD * HEAD_SHARE);
    /** Characters kept from the tail of an observation truncated at {@link #OBS_THRESHOLD}. */
    public static final int OBS_TAIL = OBS_THRESHOLD - OBS_HEAD;

    /** Rough token estimate from character count. */
    public static int estimateTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(s.length() / CHARS_PER_TOKEN);
    }

    /**
     * Estimated tokens for one message: its content plus the per-message overhead the request
     * format adds around it.
     *
     * <p>The caller passes the content as a string rather than a message object, so that this
     * class stays independent of any one provider's message type.</p>
     *
     * @param content the message's text content; {@code null} counts as empty
     * @return the estimated token count for that message
     */
    public static int estimateMessageTokens(String content) {
        return estimateTokens(content) + PER_MESSAGE_OVERHEAD;
    }

    /**
     * Truncates a large tool observation to head + tail with an elision marker, for the copy the
     * model sees. Callers log the FULL observation separately (s_iolog invariant). Short observations
     * are returned unchanged.
     */
    public static String truncateObservation(String obs) {
        return truncateObservation(obs, OBS_THRESHOLD);
    }

    /**
     * Truncates a large tool observation to head + tail with an elision marker, for the copy the
     * model sees. The whole observation is kept in the I/O log either way.
     *
     * @param obs   the observation as the tool produced it
     * @param limit how many characters the model may see; at or below zero, the default is used
     * @return the observation, or its head and tail with a marker between them
     */
    public static String truncateObservation(String obs, int limit) {
        int cap = limit > 0 ? limit : OBS_THRESHOLD;
        if (obs == null || obs.length() <= cap) return obs;
        int head = (int) (cap * HEAD_SHARE);
        int tail = cap - head;
        return obs.substring(0, head)
                + "\n…(" + (obs.length() - cap) + " characters elided)…\n"
                + obs.substring(obs.length() - tail);
    }
}
