package com.scivicslab.chatui.confine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arguments and variables agent-connector-kernel confines Claude Code with (HarnessConfinement_261121_oo01; stage 8). */
class ClaudeConfinementTest {

    private static final String DIR = "/home/u/.agent-connector-kernel";

    @Test
    void settingsAreTheOnesTheDesignNames() {
        JSONObject s = new ClaudeConfinement(DIR).settings();
        JSONObject sandbox = s.getJSONObject("sandbox");
        assertTrue(sandbox.getBoolean("enabled"));
        assertTrue(sandbox.getBoolean("failIfUnavailable"));
        assertFalse(sandbox.getBoolean("allowUnsandboxedCommands"));
        assertTrue(sandbox.getJSONArray("excludedCommands").isEmpty());
        assertEquals(List.of(DIR, "~/.ssh", "~/.claude/.credentials.json"),
                sandbox.getJSONObject("filesystem").getJSONArray("denyRead").toList());
        JSONArray files = sandbox.getJSONObject("credentials").getJSONArray("files");
        assertEquals(DIR, files.getJSONObject(0).getString("path"));
        assertEquals("deny", files.getJSONObject(0).getString("mode"));
        assertEquals("~/.ssh", files.getJSONObject(1).getString("path"));
        assertEquals("SSH_AUTH_SOCK", sandbox.getJSONObject("credentials").getJSONArray("envVars").getJSONObject(0).getString("name"));
        assertTrue(sandbox.getJSONObject("network").getJSONArray("allowedDomains").isEmpty());
        assertEquals(List.of("Read(" + DIR + "/**)", "Edit(" + DIR + "/**)", "Read(~/.ssh/**)", "Edit(~/.ssh/**)",
                "Read(~/.claude/.credentials.json)", "Edit(~/.claude/**)"),
                s.getJSONObject("permissions").getJSONArray("deny").toList());
    }

    @Test
    void argumentsCarryTheSettingsAndOnlyTheGivenMcpServer() {
        String mcp = "{\"mcpServers\":{\"agent-connector-kernel\":{\"type\":\"http\",\"url\":\"http://127.0.0.1:1/mcp\"}}}";
        List<String> args = new ClaudeConfinement(DIR).arguments(mcp);
        assertEquals("--settings", args.get(0));
        assertTrue(new JSONObject(args.get(1)).getJSONObject("sandbox").getBoolean("enabled"));
        assertEquals(List.of("--mcp-config", mcp, "--strict-mcp-config"), args.subList(2, 5));
    }

    @Test
    void proxyEnvironmentPointsAtTheProxyWithTheThrowawayKey() {
        assertEquals(Map.of("ANTHROPIC_BASE_URL", "http://127.0.0.1:2/s1", "ANTHROPIC_API_KEY", "dummy-throwaway"),
                ClaudeConfinement.proxyEnvironment("http://127.0.0.1:2/s1", "dummy-throwaway"));
    }
}
