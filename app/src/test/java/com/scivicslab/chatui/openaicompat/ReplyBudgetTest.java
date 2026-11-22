package com.scivicslab.chatui.openaicompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How many tokens a request lets the model write ({@code CutOffReplyContinues_261115_oo01}): the room left
 * in the window after the prompt, less a margin, no more than the most a reply may run to, never below a
 * floor, and sent with every request.
 */
class ReplyBudgetTest {

    /**
     * The request that was refused on project9/chat-01: a 262,144-token window, a prompt estimated at
     * 31,920 tokens that the server counted as 42,603. The reply budget and the real prompt must fit the
     * window together.
     */
    @Test
    void theRefusedRequestsNumbers_fitTheWindowTogether() {
        int window = 262_144;
        int estimated = 31_920;
        int counted = 42_603;
        int reply = OpenAiCompatProvider.replyBudgetTokens(window, estimated);
        assertTrue(reply + counted <= window, "max_tokens " + reply + " + prompt " + counted + " > window " + window);
    }

    @Test
    void unknownWindow_stillSendsABudget() {
        int reply = OpenAiCompatProvider.replyBudgetTokens(-1, 500);
        assertTrue(reply >= OpenAiCompatProvider.MIN_REPLY_TOKENS && reply <= 65_536, "sent: " + reply);
        assertEquals(reply, OpenAiCompatProvider.replyBudgetTokens(0, 500));
    }

    @Test
    void knownWindow_isTheRoomLeftLessTheMargin_whenThatIsUnderTheCap() {
        int window = 32_768;
        int prompt = 10_000;
        int margin = Math.max(1_024, (int) (window * 0.02));
        assertEquals(window - prompt - margin, OpenAiCompatProvider.replyBudgetTokens(window, prompt));
    }

    @Test
    void aLargeWindow_isCappedAtTheMostAReplyMayRunTo() {
        assertEquals(65_536, OpenAiCompatProvider.replyBudgetTokens(262_144, 10_000));
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
