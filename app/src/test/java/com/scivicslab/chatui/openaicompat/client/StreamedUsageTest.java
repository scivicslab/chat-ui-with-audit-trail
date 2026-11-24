package com.scivicslab.chatui.openaicompat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.scivicslab.chatui.core.rest.TokenUsage;

/** The tokens of a request reach the conversation from the {@code usage} chunk that ends a stream. */
class StreamedUsageTest {

    private static final List<ChatMessage> ONE_TURN = List.of(new ChatMessage.User("hello", List.of()));

    @Test
    void theLastChunkOfAStreamGivesTheFourCounts() {
        String line = "data: {\"id\":\"c1\",\"choices\":[],\"usage\":{\"prompt_tokens\":41921,"
                + "\"completion_tokens\":415,\"total_tokens\":42336}}";
        assertEquals(new TokenUsage(41921, 0, 0, 415), OpenAiCompatClient.parseSseUsage(line));
    }

    @Test
    void promptTokensTheServerSaysWereCachedAreCountedAsCacheRead() {
        String line = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":7,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":900}}}";
        TokenUsage usage = OpenAiCompatClient.parseSseUsage(line);
        assertEquals(new TokenUsage(100, 0, 900, 7), usage);
        assertEquals(1000, usage.total());
    }

    @Test
    void aChunkWithNullUsageOrNoneGivesNone() {
        assertNull(OpenAiCompatClient.parseSseUsage("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}],\"usage\":null}"));
        assertNull(OpenAiCompatClient.parseSseUsage("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"));
        assertNull(OpenAiCompatClient.parseSseUsage("data: [DONE]"));
    }

    @Test
    void aStreamingRequestAsksForTheUsageChunkAndAPlainOneDoesNot() {
        String streaming = OpenAiCompatClient.buildRequestBody("m", ONE_TURN, false, 0, true, List.of(), null);
        String plain = OpenAiCompatClient.buildRequestBody("m", ONE_TURN, false, 0, false, List.of(), null);
        assertTrue(streaming.contains("\"stream_options\":{\"include_usage\":true}"), streaming);
        assertFalse(plain.contains("stream_options"), plain);
    }
}
