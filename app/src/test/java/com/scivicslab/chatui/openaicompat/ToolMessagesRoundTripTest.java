package com.scivicslab.chatui.openaicompat;

import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The history's JSON keeps tool calls and their results (OpenAiCompatStandardToolCalling_261120_oo01, achieved state 8). */
@Tag("OpenAiCompatStandardToolCalling_261120_oo01")
@DisplayName("RequestMessagesJson round-trips tool_calls and tool messages")
class ToolMessagesRoundTripTest {

    @Test
    void toolCallsAndResultsComeBackUnchanged() {
        List<ChatMessage> messages = List.of(
                new ChatMessage.System("notes"),
                new ChatMessage.User("read notes.txt"),
                new ChatMessage.ToolCallRequest("Reading it.", List.of(
                        new ChatMessage.ToolCallRequest.ToolCall("call_1", "read", "{\"path\":\"notes.txt\"}"))),
                new ChatMessage.ToolResult("call_1", "read", "hello"),
                new ChatMessage.ToolCallRequest("", List.of(
                        new ChatMessage.ToolCallRequest.ToolCall("call_2", "calc", "{\"expression\":\"1+1\"}"))),
                new ChatMessage.ToolResult("call_2", "calc", "2"),
                new ChatMessage.Assistant("It says hello."));
        assertEquals(messages, RequestMessagesJson.parse(RequestMessagesJson.toJson(messages)));
    }

    @Test
    void aToolMessageNeedsItsCallId() {
        assertThrows(IllegalArgumentException.class,
                () -> RequestMessagesJson.parse("[{\"role\":\"tool\",\"content\":\"x\"}]"));
    }
}
