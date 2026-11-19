package com.scivicslab.chatui.openaicompat.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A call streamed in pieces is joined by index: the first piece's id and name, the arguments in the order they
 * came (OpenAiCompatStandardToolCalling_261120_oo01, achieved state 3).
 */
@Tag("OpenAiCompatStandardToolCalling_261120_oo01")
@DisplayName("delta.tool_calls pieces are joined into whole calls")
class StreamedToolCallsTest {

    static final List<String> CHUNKS = List.of(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\": \\\"no\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"tes.txt\\\"}\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");

    @Test
    void theDesignsExampleJoinsIntoOneCall() {
        OpenAiCompatClient.ToolCallAssembler calls = new OpenAiCompatClient.ToolCallAssembler();
        String finish = null;
        for (String line : CHUNKS) {
            OpenAiCompatClient.SseDelta d = OpenAiCompatClient.parseSseDelta(line);
            calls.add(d.toolCalls());
            if (d.finishReason() != null) finish = d.finishReason();
        }
        assertEquals(List.of(new ChatMessage.ToolCallRequest.ToolCall("call_1", "read", "{\"path\": \"notes.txt\"}")),
                calls.calls());
        assertEquals("tool_calls", finish);
    }

    @Test
    void twoCallsInOneReplyKeepTheirOrder() {
        OpenAiCompatClient.ToolCallAssembler calls = new OpenAiCompatClient.ToolCallAssembler();
        calls.add(OpenAiCompatClient.extractDeltaToolCalls(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"b\",\"function\":{\"name\":\"calc\",\"arguments\":\"{}\"}},"
                        + "{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}}]}"));
        assertEquals(List.of("a", "b"), calls.calls().stream().map(ChatMessage.ToolCallRequest.ToolCall::id).toList());
    }

    @Test
    void aChunkWithoutToolCallsCarriesNone() {
        assertTrue(OpenAiCompatClient.extractDeltaToolCalls("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}").isEmpty());
    }

    @Test
    void theTextWrittenWithTheCallsIsSent() {
        String body = OpenAiCompatClient.buildRequestBody("m", List.of(new ChatMessage.ToolCallRequest("I read it.",
                List.of(new ChatMessage.ToolCallRequest.ToolCall("call_1", "read", "{}")))), false, 0);
        assertTrue(body.contains("\"content\":\"I read it.\",\"tool_calls\":[{\"id\":\"call_1\""), body);
        String bare = OpenAiCompatClient.buildRequestBody("m", List.of(new ChatMessage.ToolCallRequest("",
                List.of(new ChatMessage.ToolCallRequest.ToolCall("call_1", "read", "{}")))), false, 0);
        assertTrue(bare.contains("\"content\":null,\"tool_calls\""), bare);
    }
}
