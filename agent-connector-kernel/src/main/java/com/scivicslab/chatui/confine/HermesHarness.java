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
 * https://hermes-agent.nousresearch.com/docs/reference/mcp-config-reference);</li>
 * <li>no call to the outside (tasks/todo.md, stage 9 part 3): run against a stub model, Hermes 0.23.3 looked up
 * raw.githubusercontent.com, openrouter.ai and hermes-agent.nousresearch.com, which all stopped once its model
 * catalogue was off, and api.github.com for its passive update check. {@code model_catalog.enabled: false} (hermes_cli/model_catalog.py,
 * get_catalog) and {@code updates.check: false} (hermes_cli/source_check.py, check_for_updates) turn them off. Neither
 * key is in the configuration docs; both are read from the source of v0.23.3.</li>
 * <li>our MCP server's tools listed to the model by name: by default Hermes 0.23.3 replaces MCP tools with three bridge
 * tools (tool_search, tool_describe, tool_call; tools/tool_search.py), and {@code tools.tool_search.enabled: "off"}
 * keeps them direct (tasks/todo.md, step 5). Also source-only, pinned to v0.23.3.</li>
 * </ul>
 * <p>The three source-only keys ({@code model_catalog.enabled}, {@code updates.check}, {@code tools.tool_search.enabled})
 * must be checked again whenever the Hermes version changes.</p>
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
                + "      X-Agent-Connector-Scope: \"" + scope + "\"\n"
                + "model_catalog:\n"
                + "  enabled: false\n"
                + "updates:\n"
                + "  check: false\n"
                + "tools:\n"
                + "  tool_search:\n"
                + "    enabled: \"off\"\n";
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
