package com.scivicslab.chatui.openaicompat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP client reads an answer sent as JSON or as an event stream (OpenAiCompatStandardToolCalling_261120_oo01). */
@Tag("OpenAiCompatStandardToolCalling_261120_oo01")
@DisplayName("McpToolClient reads JSON-RPC answers")
class McpToolClientTest {

    @Test
    void aJsonAnswer() {
        assertEquals(3, McpToolClient.messageOf("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}").getInt("id"));
    }

    @Test
    void anEventStreamAnswerIsItsLastAnswerLine() {
        String body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
                + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":false}}\n\n";
        assertTrue(McpToolClient.messageOf(body).has("result"));
    }

    @Test
    void noAnswer() {
        assertNull(McpToolClient.messageOf("<html>502</html>"));
    }

    @Test
    void argumentsThatAreNotJsonAreRefusedWithoutARequest() {
        McpToolClient.Result r = new McpToolClient("http://127.0.0.1:9/mcp", "project1/chat-01").callTool("read", "{path:");
        assertTrue(r.isError() && r.text().startsWith("error: the arguments are not a JSON object"), r.text());
    }

    @Test
    void anUnreachableServerIsAnErrorResult() {
        McpToolClient.Result r = new McpToolClient("http://127.0.0.1:9/mcp", "project1/chat-01").callTool("read", "{}");
        assertTrue(r.isError() && r.text().startsWith("error: "), r.text());
    }
}
