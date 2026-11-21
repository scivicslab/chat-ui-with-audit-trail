package com.scivicslab.chatui.confine;

import java.util.List;

/**
 * OpenAI Codex CLI in a Podman container (tasks/todo.md, stage 9). Its user-level configuration is
 * {@code $CODEX_HOME/config.toml}, {@code ~/.codex/config.toml} by default
 * (https://learn.chatgpt.com/codex/config-file/config-reference). The configuration:
 * <ul>
 * <li>a custom provider {@code ack} with {@code base_url} at the in-container relay and {@code env_key} naming the
 * throw-away key's variable; {@code wire_api} is left out, since {@code responses} is its only value and the default
 * (https://learn.chatgpt.com/codex/config-file/config-advanced, config-reference);</li>
 * <li>one MCP server over streamable HTTP with {@code url}, {@code bearer_token_env_var} for {@code Authorization},
 * and {@code http_headers} for the scope header (https://learn.chatgpt.com/codex/extend/mcp).</li>
 * </ul>
 */
public final class CodexHarness implements PodmanHarness {

    @Override
    public String configText(String scope, String model) {
        Names.check(scope, model);
        return "model = \"" + model + "\"\n"
                + "model_provider = \"ack\"\n"
                + "\n"
                + "[model_providers.ack]\n"
                + "name = \"agent-connector-kernel LLM proxy\"\n"
                + "base_url = \"" + PodmanConfinement.llmBaseUrl(scope) + "\"\n"
                + "env_key = \"" + PodmanConfinement.LLM_KEY_VAR + "\"\n"
                + "\n"
                + "[mcp_servers.agent-connector-kernel]\n"
                + "url = \"" + PodmanConfinement.mcpUrl() + "\"\n"
                + "bearer_token_env_var = \"" + PodmanConfinement.MCP_KEY_VAR + "\"\n"
                + "http_headers = { \"X-Agent-Connector-Scope\" = \"" + scope + "\" }\n";
    }

    @Override
    public String containerConfigPath() {
        return PodmanConfinement.HOME + "/.codex/config.toml";
    }

    @Override
    public List<String> command() {
        return List.of("codex");
    }
}
