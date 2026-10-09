package com.scivicslab.chatui.openaicompat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * A reply that the server stopped at a token limit is not an answer. The broker now puts a ceiling
 * on every request it forwards ({@code RunawayGenerationLimits_260915_oo01}), so this is the shape
 * a runaway comes back in, and the conversation has to tell it apart from a model that finished.
 */
class CutOffReplySseTest {

    private static final String STILL_RUNNING =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}";

    private static final String CUT_OFF =
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]}";

    private static final String FINISHED =
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";

    @Test
    void parseSseDelta_whileTheReplyRuns_reportsNoFinishReason() {
        // Every chunk but the last carries a JSON null there, which must not read as a reason.
        OpenAiCompatClient.SseDelta delta = OpenAiCompatClient.parseSseDelta(STILL_RUNNING);
        assertEquals("ok", delta.content());
        assertNull(delta.finishReason());
    }

    @Test
    void parseSseDelta_lastChunk_reportsWhyTheServerStopped() {
        assertEquals("length", OpenAiCompatClient.parseSseDelta(CUT_OFF).finishReason());
        assertEquals("stop", OpenAiCompatClient.parseSseDelta(FINISHED).finishReason());
    }

    @Test
    void parseNsFinishReason_aWholeResponseAlwaysHasOne() {
        assertEquals("length", OpenAiCompatClient.parseNsFinishReason(
                "{\"choices\":[{\"message\":{\"content\":\"…\"},\"finish_reason\":\"length\"}]}"));
        assertEquals("stop", OpenAiCompatClient.parseNsFinishReason("{\"choices\":[]}"));
    }

    // llama.cpp's non-streaming response writes a space after every colon, same as its streamed
    // chunks (see OpenAiCompatClientReasoningTest's llama.cpp-spacing cases). parseNsContent and
    // parseNsFinishReason read the same bytes sendNonStreaming hands back, so they must tolerate it
    // too: this is the path WebPageSummarizer and completeOutsideConversation call.
    @Test
    void parseNsFinishReason_llamaCppSpacing_readsTheValue() {
        assertEquals("length", OpenAiCompatClient.parseNsFinishReason(
                "{\"choices\": [{\"message\": {\"content\": \"…\"}, \"finish_reason\": \"length\"}]}"));
    }

    @Test
    void parseNsContent_llamaCppSpacing_readsTheValue() {
        assertEquals("the summary", OpenAiCompatClient.parseNsContent(
                "{\"choices\": [{\"message\": {\"role\": \"assistant\", \"content\": \"the summary\"}}]}"));
    }
}
