package com.scivicslab.chatui.openaicompat.client;

import com.scivicslab.chatui.openaicompat.ToolDefinition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asking the server for a temperature, and asking it for nothing.
 *
 * <p>The request carried no {@code temperature}, so every conversation ran at whatever the server
 * defaults to. Two runs of the same batch could then differ for reasons that have nothing to do
 * with the prompt, which is the thing being compared between runs.</p>
 */
@DisplayName("OpenAiCompatClient — temperature")
class RequestTemperatureTest {

    private static final List<ChatMessage> ONE_TURN =
            List.of(new ChatMessage.User("hello", List.of()));

    @Test
    void aTemperatureThatWasAskedForIsSent() {
        String body = OpenAiCompatClient.buildRequestBody("m", ONE_TURN, false, 0, true,
                List.of(), 0.0);

        assertTrue(body.contains("\"temperature\":0.0"), body);
    }

    @Test
    void aTemperatureOfAnyValueIsSentAsGiven() {
        String body = OpenAiCompatClient.buildRequestBody("m", ONE_TURN, false, 0, true,
                List.of(), 0.7);

        assertTrue(body.contains("\"temperature\":0.7"), body);
    }

    /** Nothing asked for means nothing sent: the server keeps deciding, as it did before. */
    @Test
    void noTemperatureMeansTheFieldIsAbsent() {
        String body = OpenAiCompatClient.buildRequestBody("m", ONE_TURN, false, 0, true,
                List.of(), null);

        assertFalse(body.contains("temperature"), body);
    }

    @Test
    void theOlderFormOfTheCallStillAsksForNothing() {
        String body = OpenAiCompatClient.buildRequestBody("m", ONE_TURN, false, 0);

        assertFalse(body.contains("temperature"), body);
    }

    @Test
    void theFieldSitsBesideTheOthersRatherThanBreakingTheJson() {
        String body = OpenAiCompatClient.buildRequestBody("m", ONE_TURN, false, 128, false,
                List.of(new ToolDefinition("t", "d", "{}")), 0.2);

        new org.json.JSONObject(body);   // parses, or this test fails
        assertTrue(body.contains("\"temperature\":0.2"), body);
    }
}
