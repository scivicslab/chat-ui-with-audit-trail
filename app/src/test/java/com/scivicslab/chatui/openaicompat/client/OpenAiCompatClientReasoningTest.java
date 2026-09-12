package com.scivicslab.chatui.openaicompat.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit test for the two text fields of one streamed chunk. A server that runs a thinking
 * model with a reasoning parser sends the chain of thought in {@code delta.reasoning_content} and
 * leaves {@code delta.content} null for the whole thinking phase, so a client that reads only
 * {@code content} receives nothing at all while the model reasons.
 *
 * <p>The second thing these tests hold in place is the separation: reasoning must be delivered on
 * its own callback and must not join the answer text, because the agent loop parses that text for
 * tool calls and keeps it as the final answer.</p>
 */
class OpenAiCompatClientReasoningTest {

    private static final String REASONING_CHUNK = """
            data: {"choices":[{"delta":{"role":"assistant","content":null,\
            "reasoning_content":"The user asks for 17*3. "},"index":0}]}""";

    private static final String CONTENT_CHUNK = """
            data: {"choices":[{"delta":{"role":"assistant","content":"51"},"index":0}]}""";

    @Test
    void parseSseDelta_reasoningChunk_yieldsReasoningAndNoContent() {
        OpenAiCompatClient.SseDelta delta = OpenAiCompatClient.parseSseDelta(REASONING_CHUNK);

        assertEquals("The user asks for 17*3. ", delta.reasoning());
        assertNull(delta.content(), "a thinking chunk carries no answer text");
    }

    @Test
    void parseSseDelta_contentChunk_yieldsContentAndNoReasoning() {
        OpenAiCompatClient.SseDelta delta = OpenAiCompatClient.parseSseDelta(CONTENT_CHUNK);

        assertEquals("51", delta.content());
        assertNull(delta.reasoning(), "a server that does not separate reasoning sends no such field");
    }

    @Test
    void parseSseDelta_doneMarkerAndNonDataLines_yieldNothing() {
        assertNull(OpenAiCompatClient.parseSseDelta("data: [DONE]"));
        assertNull(OpenAiCompatClient.parseSseDelta(""));
        assertNull(OpenAiCompatClient.parseSseDelta(null));
    }

    /** Records which callback each chunk reached, in order. */
    private static final class RecordingCallback implements OpenAiCompatClient.StreamCallback {
        final List<String> answer = new ArrayList<>();
        final List<String> reasoning = new ArrayList<>();

        @Override public void onDelta(String content) { answer.add(content); }
        @Override public void onReasoning(String text) { reasoning.add(text); }
        @Override public void onComplete(long durationMs) {}
        @Override public void onError(String message) {}
    }

    /**
     * Walks the same two-branch dispatch the streaming loop performs, so the separation is checked
     * without opening a socket: reasoning goes to onReasoning and to nowhere else, and only
     * content accumulates into the text the agent loop later parses.
     */
    @Test
    void reasoningReachesItsOwnCallbackAndStaysOutOfTheAnswer() {
        RecordingCallback callback = new RecordingCallback();
        StringBuilder fullResponse = new StringBuilder();

        for (String line : List.of(REASONING_CHUNK, CONTENT_CHUNK, "data: [DONE]")) {
            OpenAiCompatClient.SseDelta delta = OpenAiCompatClient.parseSseDelta(line);
            if (delta == null) continue;
            if (delta.reasoning() != null) callback.onReasoning(delta.reasoning());
            if (delta.content() != null) {
                fullResponse.append(delta.content());
                callback.onDelta(delta.content());
            }
        }

        assertEquals(List.of("The user asks for 17*3. "), callback.reasoning);
        assertEquals(List.of("51"), callback.answer);
        assertEquals("51", fullResponse.toString(),
                "the reasoning must not be part of the text kept as the answer");
    }
}
