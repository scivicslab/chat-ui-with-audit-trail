package com.scivicslab.chatui.openaicompat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A reply that the server stopped at a token limit is not an answer. The broker now puts a ceiling
 * on every request it forwards ({@code RunawayGenerationLimits_260915_oo01}), so this is the shape
 * a runaway comes back in, and the conversation has to tell it apart from a model that finished.
 */
class CutOffReplyTest {

    @Test
    void wasCutOff_onlyTheTokenLimitCounts() {
        assertTrue(OpenAiCompatProvider.wasCutOff("length"));
        assertFalse(OpenAiCompatProvider.wasCutOff("stop"));
        assertFalse(OpenAiCompatProvider.wasCutOff("tool_calls"));
        assertFalse(OpenAiCompatProvider.wasCutOff(null), "a server that says nothing has not cut anything off");
    }
}
