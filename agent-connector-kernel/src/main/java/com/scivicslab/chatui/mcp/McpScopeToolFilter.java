package com.scivicslab.chatui.mcp;

import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Keeps a request to the tools of the scope it names (tasks/todo.md, Decision 12;
 * https://docs.quarkiverse.io/quarkus-mcp-server/2.0.x/guides-using-filters-and-checks.html): a tool outside that
 * scope's share is neither listed nor called. A request that names no scope, or one that does not exist, is offered no
 * tools. When a key is required, {@link McpKeyMechanism} has already checked that the request carries the named
 * scope's key.
 */
@ApplicationScoped
public class McpScopeToolFilter implements ToolFilter {

    @Inject
    McpScopes scopes;

    @Inject
    KernelMcpServer server;

    @Override
    public boolean test(ToolInfo tool, McpConnection connection) {
        return new McpToolEndpoint(scopes::runnerOf).offers(server.scopeOfCurrentRequest(), tool.name());
    }
}
