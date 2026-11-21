package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.tool.DocsLookup;
import com.scivicslab.chatui.tool.ToolCallRecorder;
import com.scivicslab.chatui.tool.ToolExtension;
import com.scivicslab.chatui.tool.ToolRunner;
import com.scivicslab.chatui.tool.ToolScope;
import com.scivicslab.chatui.tool.ToolScopes;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request to the localhost MCP server is answered only when it names a registered scope and carries that scope's
 * key (tasks/todo.md, Decisions 5 and stage 6).
 */
class McpRequestHandlerTest {

    private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"calc\",\"arguments\":{\"expression\":\"23*47\"}}}";

    private ToolScopes scopes;
    private McpRequestHandler handler;
    private String key;

    @BeforeEach
    void setUp(@TempDir Path root) {
        scopes = new ToolScopes();
        ToolRunner runner = new ToolRunner(new ToolScope("s1", new FileAccessScope(root, List.of()), ToolSet.FULL, Map.of()),
                expression -> "1081", DocsLookup.HTTP, ToolExtension.NONE, ToolCallRecorder.NONE);
        key = scopes.register(runner);
        handler = new McpRequestHandler(scopes::has, scopes::open);
    }

    @Test
    void noKeyIsRefused() {
        assertEquals(401, handler.handle("s1", null, CALL).status());
    }

    @Test
    void aWrongKeyIsRefused() {
        assertEquals(401, handler.handle("s1", "Bearer " + key + "x", CALL).status());
        assertEquals(401, handler.handle("s1", key, CALL).status(), "the key counts only as a Bearer token");
    }

    @Test
    void theRightKeyRunsTheCall() {
        McpRequestHandler.Reply reply = handler.handle("s1", "Bearer " + key, CALL);
        assertEquals(200, reply.status());
        JSONObject result = new JSONObject(reply.body()).getJSONObject("result");
        assertFalse(result.getBoolean("isError"));
        assertEquals("1081", result.getJSONArray("content").getJSONObject(0).getString("text"));
    }

    @Test
    void theRightKeyListsTheScopesTools() {
        McpRequestHandler.Reply reply = handler.handle("s1", "Bearer " + key,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertEquals(200, reply.status());
        String names = new JSONObject(reply.body()).getJSONObject("result").getJSONArray("tools").toString();
        assertTrue(names.contains("\"read\"") && names.contains("\"calc\""), names);
    }

    @Test
    void anUnknownScopeIsRefused() {
        assertEquals(404, handler.handle("s2", "Bearer " + key, CALL).status());
        assertEquals(400, handler.handle(null, "Bearer " + key, CALL).status());
    }
}
