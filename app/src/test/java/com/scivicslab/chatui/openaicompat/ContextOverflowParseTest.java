package com.scivicslab.chatui.openaicompat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * What a context-length refusal says about the window and the prompt's real size, in the forms the servers
 * here write, and the cap a provider can be given.
 */
class ContextOverflowParseTest {

    @Test
    void theGlmServersMessage() {
        var o = OpenAiCompatProvider.ContextOverflow.parse("This server's maximum context length is 262144 tokens: "
                + "this request needs a 262151-token context (7 prompt tokens plus max_tokens 262144), which exceeds "
                + "the context window this server was started for");
        assertEquals(262_144, o.window());
        assertEquals(7, o.promptTokens());
    }

    @Test
    void vllmsMessage() {
        var o = OpenAiCompatProvider.ContextOverflow.parse("This model's maximum context length is 262144 tokens. "
                + "However, you requested 267585 tokens (42603 in the messages, 224982 in the completion).");
        assertEquals(262_144, o.window());
        assertEquals(42_603, o.promptTokens());
    }

    @Test
    void aMessageWithNoNumbers() {
        var o = OpenAiCompatProvider.ContextOverflow.parse("context length exceeded");
        assertEquals(-1, o.window());
        assertEquals(-1, o.promptTokens());
        assertEquals(-1, OpenAiCompatProvider.ContextOverflow.parse(null).window());
    }

    @Test
    void theCapCanBeSetHigherOrLower() {
        assertEquals(100_000, OpenAiCompatProvider.replyBudgetTokens(262_144, 1_000, 100_000));
        assertEquals(4_096, OpenAiCompatProvider.replyBudgetTokens(262_144, 1_000, 4_096));
    }
}
