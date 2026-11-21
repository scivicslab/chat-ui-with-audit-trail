package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.tool.ToolRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The MCP tools of a scope, with no transport in sight: which tools a scope is offered and what a call returns
 * ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}, tasks/todo.md stage 6). chat-ui's Quarkus {@code /mcp} and
 * agent-connector-kernel's localhost HTTP server both call it; neither passes an HTTP detail in.
 */
public final class McpToolEndpoint {

    /**
     * What a {@code tools/call} returned.
     *
     * @param text    the observation
     * @param isError whether it is an error, i.e. starts with {@code error: }
     */
    public record Result(String text, boolean isError) {
    }

    private final Function<String, ToolRunner> scopes;

    /** @param scopes finds the runner of a scope name, or returns {@code null} when there is no such scope */
    public McpToolEndpoint(Function<String, ToolRunner> scopes) {
        this.scopes = scopes;
    }

    /** @return whether scope {@code scopeName} exists and is offered {@code tool} */
    public boolean offers(String scopeName, String tool) {
        ToolRunner runner = scopeName == null ? null : scopes.apply(scopeName);
        return runner != null && runner.offers(tool);
    }

    /**
     * @param scopeName the scope the request names
     * @param tool      the tool's name
     * @param argsJson  the call's arguments as a JSON object text
     * @return the observation; a scope that does not exist is an error
     */
    public Result call(String scopeName, String tool, String argsJson) {
        ToolRunner runner = scopeName == null ? null : scopes.apply(scopeName);
        if (runner == null) return new Result("error: no such conversation: " + scopeName, true);
        String observation = runner.callFromMcp(tool, argsJson);
        return new Result(observation, observation.startsWith("error: "));
    }

    /**
     * @return the definitions scope {@code scopeName} is offered: the core tools and the plugin tools it may call;
     *         empty when there is no such scope
     */
    public List<ToolCatalog.Spec> list(String scopeName) {
        ToolRunner runner = scopeName == null ? null : scopes.apply(scopeName);
        List<ToolCatalog.Spec> specs = new ArrayList<>();
        if (runner == null) return specs;
        ToolCatalog.coreTools().values().stream().filter(s -> runner.offers(s.name())).forEach(specs::add);
        runner.scope().pluginTools().values().stream().filter(t -> runner.offers(t.name()))
                .map(ToolCatalog::ofPlugin).forEach(specs::add);
        return specs;
    }
}
