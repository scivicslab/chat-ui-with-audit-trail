package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.rest.ChatEvent;

import java.util.function.Consumer;

/**
 * Sorts one agent-loop step's streamed text into a provisional answer and a tool call.
 *
 * <p>A step's text is the final answer only if it holds no tool call, and that is known only when
 * the step ends. Waiting until then shows nothing for the whole of the answer. This shows the text
 * as it arrives, as {@code answer_delta} events, and takes it back with {@code answer_retract} if a
 * tool-call spelling turns up: the text already shown is moved to the thinking trace by the pane,
 * and the rest of the step goes to the trace as {@code thinking} events, as it always has. When the
 * step ends without a call the turn's own {@code delta} replaces the provisional text with the
 * confirmed answer, so a later rewrite of the answer still wins.</p>
 *
 * <p>The last few characters are held back while they could be the start of a tool-call spelling,
 * so a half-typed {@code <invo} is never shown and then taken back.</p>
 */
final class StepAnswerStream {

    /** Where a tool call begins, in each spelling {@code TextToolCallParser} reads. */
    private static final String[] MARKERS = {"<invoke", "<function_calls", "<tool_call>", "<function="};

    private final Consumer<ChatEvent> emitter;
    private final StringBuilder text = new StringBuilder();
    /** How many leading characters of {@link #text} the pane has been sent as the provisional answer. */
    private int shown;
    private boolean toolCall;

    StepAnswerStream(Consumer<ChatEvent> emitter) {
        this.emitter = emitter;
    }

    /** Takes the next piece of the step's streamed text. */
    void accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        text.append(chunk);
        if (toolCall) {
            emitter.accept(ChatEvent.thinking(chunk));
            return;
        }
        int at = firstMarker();
        if (at >= 0) {
            becomeToolCall();
            return;
        }
        int safe = text.length() - heldBack();
        if (safe > shown) {
            emitter.accept(ChatEvent.answerDelta(text.substring(shown, safe)));
            shown = safe;
        }
    }

    /**
     * Called when the step's reply is complete.
     *
     * @param hasCalls whether the parser found a tool call in the whole reply, which can happen
     *                 for a spelling this class did not recognise as it streamed
     */
    void finish(boolean hasCalls) {
        if (toolCall) return;
        if (hasCalls) {
            becomeToolCall();
        } else if (shown < text.length()) {
            emitter.accept(ChatEvent.answerDelta(text.substring(shown)));
            shown = text.length();
        }
    }

    private void becomeToolCall() {
        toolCall = true;
        if (shown > 0) emitter.accept(ChatEvent.answerRetract());
        if (shown < text.length()) emitter.accept(ChatEvent.thinking(text.substring(shown)));
        shown = 0;
    }

    /**
     * @return where the first tool call begins, or -1. Read as {@code TextToolCallParser} reads: a
     *         marker counts only at the start of a line (spaces allowed) and outside a fenced code
     *         block, so a quoted block does not take the answer back. A fence still open when the text
     *         ends is treated as open here; the parser, which removes only closed fences, settles it
     *         in {@link #finish}.
     */
    private int firstMarker() {
        int best = -1;
        for (String marker : MARKERS) {
            for (int i = text.indexOf(marker, shown); i >= 0; i = text.indexOf(marker, i + 1)) {
                if (best >= 0 && i >= best) break;
                if (startsALine(i) && !insideFence(i)) {
                    best = i;
                    break;
                }
            }
        }
        return best;
    }

    /** @return whether only spaces and tabs lie between the start of the line and {@code at} */
    private boolean startsALine(int at) {
        for (int k = at - 1; k >= 0; k--) {
            char c = text.charAt(k);
            if (c == '\n') return true;
            if (c != ' ' && c != '\t') return false;
        }
        return true;
    }

    /** @return whether {@code at} lies after an odd number of code fences, i.e. inside one */
    private boolean insideFence(int at) {
        int fences = 0;
        for (int k = text.indexOf("```"); k >= 0 && k < at; k = text.indexOf("```", k + 3)) fences++;
        return fences % 2 == 1;
    }

    /** How many trailing characters could still grow into a marker. */
    private int heldBack() {
        int held = 0;
        int len = text.length();
        for (String marker : MARKERS) {
            int most = Math.min(marker.length() - 1, len - shown);
            for (int k = most; k > held; k--) {
                if (text.indexOf(marker.substring(0, k), len - k) == len - k) {
                    held = k;
                    break;
                }
            }
        }
        return held;
    }
}
