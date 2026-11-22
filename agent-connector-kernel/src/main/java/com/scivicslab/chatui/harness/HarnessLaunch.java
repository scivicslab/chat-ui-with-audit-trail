package com.scivicslab.chatui.harness;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * How a harness CLI process is started: its command line, the environment variables removed from and added to what
 * it inherits, and its working directory (tasks/todo.md, stage 7). Part of agent-connector-kernel since stage 11, so
 * that any product can start a harness with it: chat-ui's {@code plugin-harness} and ssh-connector.
 *
 * @param command    the command line
 * @param removedEnv the inherited variables the process must not see
 * @param addedEnv   the variables set for the process
 * @param workingDir the working directory, or {@code null} for this process's own
 */
public record HarnessLaunch(List<String> command, List<String> removedEnv, Map<String, String> addedEnv, Path workingDir) {

    public HarnessLaunch {
        command = List.copyOf(command);
        removedEnv = List.copyOf(removedEnv);
        addedEnv = Map.copyOf(addedEnv);
    }

    /** @return a process builder set up as this launch says; standard error is not merged into standard output */
    public ProcessBuilder processBuilder() {
        ProcessBuilder pb = new ProcessBuilder(command);
        removedEnv.forEach(pb.environment()::remove);
        pb.environment().putAll(addedEnv);
        if (workingDir != null) pb.directory(new File(workingDir.toString()));
        pb.redirectErrorStream(false);
        return pb;
    }

    /**
     * The MCP server a harness is handed (tasks/todo.md, Decisions 5): agent-connector-kernel's server on localhost
     * over HTTP, the scope the harness works in, and that scope's key. There is no stdio form.
     *
     * @param url   the server's address, e.g. {@code http://127.0.0.1:41234/mcp}
     * @param scope the scope name the requests carry
     * @param key   the scope's key
     */
    public record McpEntry(String url, String scope, String key) {

        /** The server's name in the harness's MCP configuration. */
        public static final String SERVER_NAME = "agent-connector-kernel";

        /** @return Claude Code's {@code --mcp-config} value: one HTTP server with the scope and key headers */
        public String claudeMcpConfig() {
            JSONObject headers = new JSONObject().put("Authorization", "Bearer " + key)
                    .put("X-Agent-Connector-Scope", scope);
            JSONObject server = new JSONObject().put("type", "http").put("url", url).put("headers", headers);
            return new JSONObject().put("mcpServers", new JSONObject().put(SERVER_NAME, server)).toString();
        }
    }
}
