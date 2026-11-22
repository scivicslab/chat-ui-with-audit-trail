package com.scivicslab.chatui.confine;

import org.json.JSONObject;

import java.util.List;

/**
 * OpenCode in a Podman container (tasks/todo.md, stage 9), in the shape of OpenCode 1, the latest release
 * (v1.19.6). Its global configuration is {@code ~/.config/opencode/opencode.json} (https://opencode.ai/v1/docs/config).
 * The configuration:
 * <ul>
 * <li>a custom provider {@code ack} with {@code "npm": "@ai-sdk/openai-compatible"} and {@code options.baseURL} at the
 * in-container relay (https://opencode.ai/v1/docs/providers), the key as {@code options.apiKey} with
 * {@code {env:NAME}} substitution;</li>
 * <li>one remote MCP server under {@code mcp} with {@code type: "remote"}, {@code url}, {@code oauth: false} and
 * {@code headers}, the key as {@code {env:NAME}} (https://opencode.ai/v1/docs/mcp-servers);</li>
 * <li>{@code autoupdate: false}, so OpenCode does not look for a newer release (https://opencode.ai/v1/docs/config).
 * The image does the rest of stage 9 part 3's "no call to the outside": it holds the {@code @opencode-ai/plugin}
 * package OpenCode installs into its config directory, and sets {@code OPENCODE_DISABLE_MODELS_FETCH}
 * (containers/opencode/Containerfile).</li>
 * </ul>
 */
public final class OpenCodeHarness implements PodmanHarness {

    @Override
    public String configText(String scope, String model) {
        Names.check(scope, model);
        JSONObject provider = new JSONObject()
                .put("npm", "@ai-sdk/openai-compatible")
                .put("name", "agent-connector-kernel LLM proxy")
                .put("options", new JSONObject().put("baseURL", PodmanConfinement.llmBaseUrl(scope))
                        .put("apiKey", "{env:" + PodmanConfinement.LLM_KEY_VAR + "}"))
                .put("models", new JSONObject().put(model, new JSONObject().put("name", model)));
        JSONObject mcp = new JSONObject()
                .put("type", "remote")
                .put("url", PodmanConfinement.mcpUrl())
                .put("enabled", true)
                .put("oauth", false)
                .put("headers", new JSONObject()
                        .put("Authorization", "Bearer {env:" + PodmanConfinement.MCP_KEY_VAR + "}")
                        .put("X-Agent-Connector-Scope", scope));
        return new JSONObject()
                .put("$schema", "https://opencode.ai/config.json")
                .put("model", "ack/" + model)
                .put("autoupdate", false)
                .put("provider", new JSONObject().put("ack", provider))
                .put("mcp", new JSONObject().put("agent-connector-kernel", mcp))
                .toString(2) + "\n";
    }

    @Override
    public String containerConfigPath() {
        return PodmanConfinement.HOME + "/.config/opencode/opencode.json";
    }

    @Override
    public List<String> command() {
        return List.of("opencode");
    }
}
