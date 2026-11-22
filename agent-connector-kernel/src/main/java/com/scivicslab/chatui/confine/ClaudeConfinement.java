package com.scivicslab.chatui.confine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * How Claude Code is confined when agent-connector-kernel launches it ({@code HarnessConfinement_261121_oo01};
 * tasks/todo.md stage 8): the {@code --settings} it is started with, the MCP servers it may use, and the LLM
 * proxy it talks to. A plain object that only builds arguments and variables; any product's launcher can hand them
 * to the process it starts.
 */
public final class ClaudeConfinement {

    private final String kernelDir;

    /** @param kernelDir agent-connector-kernel's directory, which holds the secrets the harness must not read */
    public ClaudeConfinement(String kernelDir) {
        this.kernelDir = kernelDir;
    }

    /**
     * @return the {@code --settings} JSON of {@code HarnessConfinement_261121_oo01}: the sandbox on, never run outside
     *         it, the secret files and {@code SSH_AUTH_SOCK} denied to its commands, no network for them, and Read and
     *         Edit denied on the secrets and on Claude Code's own settings
     */
    public JSONObject settings() {
        JSONObject sandbox = new JSONObject()
                .put("enabled", true)
                .put("failIfUnavailable", true)
                .put("allowUnsandboxedCommands", false)
                .put("excludedCommands", new JSONArray())
                .put("filesystem", new JSONObject().put("denyRead",
                        new JSONArray().put(kernelDir).put("~/.ssh").put("~/.claude/.credentials.json")))
                .put("credentials", new JSONObject()
                        .put("files", new JSONArray()
                                .put(new JSONObject().put("path", kernelDir).put("mode", "deny"))
                                .put(new JSONObject().put("path", "~/.ssh").put("mode", "deny")))
                        .put("envVars", new JSONArray()
                                .put(new JSONObject().put("name", "SSH_AUTH_SOCK").put("mode", "deny"))))
                .put("network", new JSONObject().put("allowedDomains", new JSONArray()));
        JSONArray deny = new JSONArray()
                .put("Read(" + kernelDir + "/**)")
                .put("Edit(" + kernelDir + "/**)")
                .put("Read(~/.ssh/**)")
                .put("Edit(~/.ssh/**)")
                .put("Read(~/.claude/.credentials.json)")
                .put("Edit(~/.claude/**)");
        return new JSONObject().put("sandbox", sandbox).put("permissions", new JSONObject().put("deny", deny));
    }

    /**
     * @param mcpConfigJson the {@code --mcp-config} value naming agent-connector-kernel's MCP server only
     * @return the arguments that confine Claude Code: {@code --settings}, and {@code --mcp-config} with
     *         {@code --strict-mcp-config}, so no MCP server the user registered elsewhere is used
     */
    public List<String> arguments(String mcpConfigJson) {
        return List.of("--settings", settings().toString(), "--mcp-config", mcpConfigJson, "--strict-mcp-config");
    }

    /**
     * @param proxyBaseUrl the LLM proxy's base URL for this harness's scope
     * @param throwawayKey the scope's throw-away key
     * @return the variables that point Claude Code at the LLM proxy ({@code LlmKeyInjectionProxy_261121_oo01})
     */
    public static Map<String, String> proxyEnvironment(String proxyBaseUrl, String throwawayKey) {
        return Map.of("ANTHROPIC_BASE_URL", proxyBaseUrl, "ANTHROPIC_API_KEY", throwawayKey);
    }
}
