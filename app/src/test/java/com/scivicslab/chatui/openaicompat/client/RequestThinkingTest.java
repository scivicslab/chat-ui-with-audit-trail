package com.scivicslab.chatui.openaicompat.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Think box decides whether the model thinks, on every server ({@code ThinkFlagAlwaysSent_261011_oo01}).
 * The servers serving one model default differently -- Strata thinks unless told not to, vLLM and
 * TensorFold do not unless told to -- so leaving the field out when the box is ticked handed the
 * decision to whichever server the queue picked.
 */
@Tag("ThinkFlagAlwaysSent_261011_oo01")
@DisplayName("Think のチェックは、どのサーバにも必ず指定として届く")
class RequestThinkingTest {

    private static final List<ChatMessage> ONE_TURN = List.of(new ChatMessage.User("hello", List.of()));

    @Test
    @DisplayName("チェックが入っていれば enable_thinking=true を送る")
    void thinkTicked_sendsTrue() {
        String body = OpenAiCompatClient.buildRequestBody("m", ONE_TURN, false, 0, true, List.of(), null);
        assertTrue(body.contains("\"chat_template_kwargs\":{\"enable_thinking\":true}"), body);
    }

    @Test
    @DisplayName("チェックが外れていれば enable_thinking=false を送る")
    void thinkUnticked_sendsFalse() {
        String body = OpenAiCompatClient.buildRequestBody("m", ONE_TURN, true, 0, true, List.of(), null);
        assertTrue(body.contains("\"chat_template_kwargs\":{\"enable_thinking\":false}"), body);
    }
}
