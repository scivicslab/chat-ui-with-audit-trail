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
                + "base_url = \"http://127.0.0.1:18766/s1/v1\"\nenv_key = \"ACK_LLM_KEY\"\n"), toml);
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
        assertEquals("http://127.0.0.1:18766/s1/v1", provider.getJSONObject("options").getString("baseURL"));
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
        assertEquals("model:\n  default: \"glm-5.3\"\n  provider: custom\n  base_url: \"http://127.0.0.1:18766/s1/v1\"\n"
                + "  key_env: ACK_LLM_KEY\nmcp_servers:\n  agent-connector-kernel:\n"
                + "    url: \"http://127.0.0.1:18765/mcp\"\n    headers:\n"
                + "      Authorization: \"Bearer ${ACK_MCP_KEY}\"\n      X-Agent-Connector-Scope: \"s1\"\n", yaml);
        assertEquals("/home/agent/.hermes/config.yaml", h.containerConfigPath());
        assertEquals(List.of("hermes"), h.command());
    }

    @Test
    void aNameThatCouldBreakOutOfAQuotedStringIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CodexHarness().configText("s1\"\n[x]", "m"));
        assertThrows(IllegalArgumentException.class, () -> new HermesHarness().configText("s1", "m\n  evil: 1"));
    }
}
