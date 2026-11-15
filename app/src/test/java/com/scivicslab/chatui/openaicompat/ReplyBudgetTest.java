package com.scivicslab.chatui.openaicompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How many tokens a request lets the model write ({@code CutOffReplyContinues_261115_oo01}): the room left
 * in the window after the prompt, less a margin, never below a floor, and nothing at all when the window is
 * unknown.
 */
class ReplyBudgetTest {

    @Test
    void unknownWindow_sendsNothing() {
        assertEquals(0, OpenAiCompatProvider.replyBudgetTokens(-1, 500));
        assertEquals(0, OpenAiCompatProvider.replyBudgetTokens(0, 500));
    }

    @Test
    void knownWindow_isTheRoomLeftLessTheMargin() {
        int window = 262_144;
        int prompt = 10_000;
        int margin = (int) (window * 0.02);
        assertEquals(window - prompt - margin, OpenAiCompatProvider.replyBudgetTokens(window, prompt));
    }

    @Test
    void smallWindow_keepsTheMinimumMargin() {
        // 2% of 8192 is 163, below the 1024 minimum
        assertEquals(8_192 - 1_000 - 1_024, OpenAiCompatProvider.replyBudgetTokens(8_192, 1_000));
    }

    @Test
    void aWindowAlreadyFull_stillAllowsTheFloor() {
        assertEquals(OpenAiCompatProvider.MIN_REPLY_TOKENS, OpenAiCompatProvider.replyBudgetTokens(8_192, 8_000));
        assertEquals(OpenAiCompatProvider.MIN_REPLY_TOKENS, OpenAiCompatProvider.replyBudgetTokens(8_192, 50_000));
    }

    @Test
    void neverMoreThanTheWindow() {
        assertTrue(OpenAiCompatProvider.replyBudgetTokens(1_000, 0) <= 1_000);
    }
}
