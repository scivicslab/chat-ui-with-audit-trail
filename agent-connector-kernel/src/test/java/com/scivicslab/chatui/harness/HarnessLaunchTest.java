package com.scivicslab.chatui.harness;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch of a harness process, and the MCP entry it can be handed (tasks/todo.md, Decisions 5 and stage 7). In
 * agent-connector-kernel since stage 11, so that any product, ssh-connector among them, can start a harness.
 */
class HarnessLaunchTest {

    @Test
    void processBuilderRemovesAndAddsVariablesAndSetsTheDirectory(@TempDir Path dir) {
        ProcessBuilder pb = new HarnessLaunch(List.of("true"), List.of("PATH"), Map.of("PINNED", "1"), dir).processBuilder();
        assertFalse(pb.environment().containsKey("PATH"));
        assertEquals("1", pb.environment().get("PINNED"));
        assertEquals(dir.toFile(), pb.directory());
        assertFalse(pb.redirectErrorStream());
    }

    @Test
    void theMcpEntryIsAnHttpUrlWithTheScopeAndTheKey() {
        JSONObject config = new JSONObject(new HarnessLaunch.McpEntry("http://127.0.0.1:41234/mcp", "s1", "k1").claudeMcpConfig());
        JSONObject server = config.getJSONObject("mcpServers").getJSONObject(HarnessLaunch.McpEntry.SERVER_NAME);
        assertEquals("http", server.getString("type"));
        assertEquals("http://127.0.0.1:41234/mcp", server.getString("url"));
        assertEquals("Bearer k1", server.getJSONObject("headers").getString("Authorization"));
        assertEquals("s1", server.getJSONObject("headers").getString("X-Agent-Connector-Scope"));
        assertFalse(server.has("command"), "no stdio form");
    }
}
