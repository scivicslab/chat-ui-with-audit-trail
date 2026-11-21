package com.scivicslab.chatui.confine;

import java.util.List;

/**
 * Hermes Agent in a Podman container (tasks/todo.md, stage 9). Its configuration is {@code ~/.hermes/config.yaml}
 * (https://hermes-agent.nousresearch.com/docs/user-guide/configuration). The configuration:
 * <ul>
 * <li>{@code model} with {@code provider: custom}, {@code base_url} at the in-container relay and {@code key_env}
 * naming the throw-away key's variable (https://hermes-agent.nousresearch.com/docs/integrations/providers);</li>
 * <li>one MCP server under {@code mcp_servers} with {@code url} and {@code headers}, the key as {@code ${NAME}}, which
 * Hermes expands in any string of a server entry (https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp,
 * https://hermes-agent.nousresearch.com/docs/reference/mcp-config-reference).</li>
 * </ul>
 */
public final class HermesHarness implements PodmanHarness {

    @Override
    public String configText(String scope, String model) {
        Names.check(scope, model);
        return "model:\n"
                + "  default: \"" + model + "\"\n"
                + "  provider: custom\n"
                + "  base_url: \"" + PodmanConfinement.llmBaseUrl(scope) + "\"\n"
                + "  key_env: " + PodmanConfinement.LLM_KEY_VAR + "\n"
                + "mcp_servers:\n"
                + "  agent-connector-kernel:\n"
                + "    url: \"" + PodmanConfinement.mcpUrl() + "\"\n"
                + "    headers:\n"
                + "      Authorization: \"Bearer ${" + PodmanConfinement.MCP_KEY_VAR + "}\"\n"
                + "      X-Agent-Connector-Scope: \"" + scope + "\"\n";
    }

    @Override
    public String containerConfigPath() {
        return PodmanConfinement.HOME + "/.hermes/config.yaml";
    }

    @Override
    public List<String> command() {
        return List.of("hermes");
    }
}
