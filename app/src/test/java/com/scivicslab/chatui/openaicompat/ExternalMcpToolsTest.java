package com.scivicslab.chatui.openaicompat;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An external MCP server's tools reach the model as {@code <server>__<tool>} and their calls go to that server under
 * the tool's own name (ExternalMcpServersOfAConversation_261120_oo01, achieved states 4 and 5).
 */
@Tag("ExternalMcpServersOfAConversation_261120_oo01")
@DisplayName("OpenAiCompatProvider lists and calls an external MCP server's tools under its name")
class ExternalMcpToolsTest {

    HttpServer server;
    final List<JSONObject> calls = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            JSONObject req = new JSONObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            JSONObject result;
            if ("tools/list".equals(req.getString("method"))) {
                result = new JSONObject().put("tools", new JSONArray()
                        .put(new JSONObject().put("name", "search").put("description", "Search documents.")
                                .put("inputSchema", new JSONObject().put("type", "object")))
                        .put(new JSONObject().put("name", "bad name").put("description", "x")));
            } else {
                synchronized (calls) { calls.add(req.getJSONObject("params")); }
                result = new JSONObject().put("isError", false).put("content", new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "found 3 documents")));
            }
            byte[] b = new JSONObject().put("jsonrpc", "2.0").put("id", req.get("id")).put("result", result)
                    .toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    OpenAiCompatProvider provider() {
        OpenAiCompatProvider p = new OpenAiCompatProvider(List.of("http://127.0.0.1:9"), "m");
        p.setMcpServers(Map.of("docs", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"));
        p.setSystemMessage("S");
        return p;
    }

    @Test
    void theToolIsGivenUnderTheServersName() {
        List<String> names = provider().tools().stream().map(ToolDefinition::name).toList();
        assertTrue(names.contains("docs__search"), names.toString());
        assertFalse(names.stream().anyMatch(n -> n.contains("bad name")), "a name OpenAI refuses is not given");
        assertTrue(names.contains("ask_person"), "the conversation's own tools are still given");
    }

    @Test
    void theCallGoesToThatServerUnderTheToolsOwnName() {
        assertEquals("found 3 documents", provider().callTool("docs__search", "{\"query\":\"actor\"}"));
        assertEquals("search", calls.get(0).getString("name"));
        assertEquals("actor", calls.get(0).getJSONObject("arguments").getString("query"));
    }

    @Test
    void anUnreachableServerLeavesTheOtherToolsGiven() {
        OpenAiCompatProvider p = new OpenAiCompatProvider(List.of("http://127.0.0.1:9"), "m");
        p.setMcpServers(Map.of("gone", "http://127.0.0.1:9/mcp"));
        p.setSystemMessage("S");
        List<String> names = p.tools().stream().map(ToolDefinition::name).toList();
        assertFalse(names.stream().anyMatch(n -> n.startsWith("gone__")));
        assertTrue(names.contains("ask_person"));
    }
}
