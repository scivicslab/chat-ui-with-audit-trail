package com.scivicslab.chatui.confine;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each Podman harness's configuration points its MCP client and its LLM client at the in-container relay, with the
 * keys only as variable references (tasks/todo.md, stage 9 part 1).
 */
class HarnessConfigTest {

    @Test
    void codex() {
        CodexHarness h = new CodexHarness();
        String toml = h.configText("s1", "gpt-5.5");
        assertTrue(toml.contains("model_provider = \"ack\""), toml);
        assertTrue(toml.contains("[model_providers.ack]\nname = \"agent-connector-kernel LLM proxy\"\n"
                + "base_url = \"http://127.0.0.1:18766/llm/s1/v1\"\nenv_key = \"ACK_LLM_KEY\"\n"), toml);
        assertTrue(toml.contains("[mcp_servers.agent-connector-kernel]\nurl = \"http://127.0.0.1:18765/mcp\"\n"
                + "bearer_token_env_var = \"ACK_MCP_KEY\"\n"
                + "http_headers = { \"X-Agent-Connector-Scope\" = \"s1\" }\n"), toml);
        assertEquals("/home/agent/.codex/config.toml", h.containerConfigPath());
        assertEquals(List.of("codex"), h.command());
    }

    @Test
    void openCode() {
        OpenCodeHarness h = new OpenCodeHarness();
        JSONObject c = new JSONObject(h.configText("s1", "glm-5.3"));
        JSONObject provider = c.getJSONObject("provider").getJSONObject("ack");
        assertEquals("@ai-sdk/openai-compatible", provider.getString("npm"));
        assertEquals("http://127.0.0.1:18766/llm/s1/v1", provider.getJSONObject("options").getString("baseURL"));
        assertEquals("{env:ACK_LLM_KEY}", provider.getJSONObject("options").getString("apiKey"));
        assertTrue(provider.getJSONObject("models").has("glm-5.3"));
        assertEquals("ack/glm-5.3", c.getString("model"));
        JSONObject mcp = c.getJSONObject("mcp").getJSONObject("agent-connector-kernel");
        assertEquals("remote", mcp.getString("type"));
        assertEquals("http://127.0.0.1:18765/mcp", mcp.getString("url"));
        assertFalse(mcp.getBoolean("oauth"));
        assertEquals("Bearer {env:ACK_MCP_KEY}", mcp.getJSONObject("headers").getString("Authorization"));
        assertEquals("s1", mcp.getJSONObject("headers").getString("X-Agent-Connector-Scope"));
        assertEquals("/home/agent/.config/opencode/opencode.json", h.containerConfigPath());
        assertEquals(List.of("opencode"), h.command());
    }

    @Test
    void hermes() {
        HermesHarness h = new HermesHarness();
        String yaml = h.configText("s1", "glm-5.3");
        assertEquals("model:\n  default: \"glm-5.3\"\n  provider: custom\n  base_url: \"http://127.0.0.1:18766/llm/s1/v1\"\n"
                + "  key_env: ACK_LLM_KEY\nmcp_servers:\n  agent-connector-kernel:\n"
                + "    url: \"http://127.0.0.1:18765/mcp\"\n    headers:\n"
                + "      Authorization: \"Bearer ${ACK_MCP_KEY}\"\n      X-Agent-Connector-Scope: \"s1\"\n"
                + "model_catalog:\n  enabled: false\nupdates:\n  check: false\n", yaml);
        assertEquals("/home/agent/.hermes/config.yaml", h.containerConfigPath());
        assertEquals(List.of("hermes"), h.command());
    }

    /**
     * With --network=none every outside call fails anyway, but the harness must not try one (stage 9 part 3). Codex
     * 0.171.0, run against a stub model, looked up api.github.com (curated plugin sync), ab.chatgpt.com and chatgpt.com;
     * the documented switches below turn those calls off (https://learn.chatgpt.com/codex/config-file/config-reference).
     */
    @Test
    void codexTurnsOffItsStartupCallsToTheOutside() {
        String toml = new CodexHarness().configText("s1", "gpt-5.5");
        assertTrue(toml.startsWith("check_for_update_on_startup = false\n"), "a top-level key, before any table: " + toml);
        assertTrue(toml.contains("\n[analytics]\nenabled = false\n"), toml);
        assertTrue(toml.contains("\n[features]\nplugins = false\nremote_plugin = false\napps = false\n"), toml);
    }

    /** OpenCode 1.19.6's update check is off ({@code autoupdate}, https://opencode.ai/v1/docs/config). */
    @Test
    void openCodeTurnsOffItsUpdateCheck() {
        JSONObject c = new JSONObject(new OpenCodeHarness().configText("s1", "glm-5.3"));
        assertFalse(c.getBoolean("autoupdate"), c.toString());
    }

    /**
     * Hermes Agent 0.23.3 looked up raw.githubusercontent.com and others for its model catalogue, and api.github.com
     * for its passive update check. Both are off: {@code model_catalog.enabled} (hermes_cli/model_catalog.py) and
     * {@code updates.check} (hermes_cli/source_check.py, check_for_updates).
     */
    @Test
    void hermesTurnsOffItsModelCatalogueAndUpdateCheck() {
        String yaml = new HermesHarness().configText("s1", "glm-5.3");
        assertTrue(yaml.contains("\nmodel_catalog:\n  enabled: false\n"), yaml);
        assertTrue(yaml.contains("\nupdates:\n  check: false\n"), yaml);
    }

    @Test
    void aNameThatCouldBreakOutOfAQuotedStringIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CodexHarness().configText("s1\"\n[x]", "m"));
        assertThrows(IllegalArgumentException.class, () -> new HermesHarness().configText("s1", "m\n  evil: 1"));
    }
}
