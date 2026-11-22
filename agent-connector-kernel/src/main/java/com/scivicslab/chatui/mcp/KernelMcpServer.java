package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.agent.ToolCatalog;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * agent-connector-kernel's MCP server, on Quarkiverse quarkus-mcp-server (tasks/todo.md, Decision 12). The one MCP
 * server of an application: chat-ui's {@code /mcp}, and later ssh-connector's.
 *
 * <p>At start-up it registers every tool {@link McpScopes#tools()} names, with {@code ToolManager}
 * (https://docs.quarkiverse.io/quarkus-mcp-server/2.0.x/guides-implementing-tools.html, "Programmatic API"). A request
 * names its scope in the scope header ({@code agent-connector-kernel.mcp.scope-header}, by default
 * {@value McpAccess#DEFAULT_SCOPE_HEADER}); {@link McpScopeToolFilter} lists only that scope's tools, and a call runs
 * with that scope's runner on the thread that took the request. The key is checked before, by
 * {@link McpKeyMechanism}.</p>
 */
@ApplicationScoped
public class KernelMcpServer {

    private static final Logger LOG = Logger.getLogger(KernelMcpServer.class.getName());

    @Inject
    ToolManager toolManager;

    @Inject
    McpScopes scopes;

    @Inject
    CurrentVertxRequest currentRequest;

    @ConfigProperty(name = "agent-connector-kernel.mcp.scope-header", defaultValue = McpAccess.DEFAULT_SCOPE_HEADER)
    String scopeHeader;

    void register(@Observes StartupEvent event) {
        List<ToolCatalog.Spec> specs = scopes.tools();
        for (ToolCatalog.Spec spec : specs) {
            toolManager.newTool(spec.name())
                    .setDescription(spec.description())
                    .setInputSchema(new JsonObject(spec.inputSchema().toString()))
                    .setHandler(arguments -> call(spec.name(), arguments.args()), true)
                    .register();
        }
        LOG.info("MCP server offers " + specs.size() + " tools");
    }

    /** Runs one {@code tools/call}; an observation that starts with {@code error: } is returned as an error result. */
    private ToolResponse call(String tool, Map<String, Object> arguments) {
        String scope = scopeOfCurrentRequest();
        if (scope == null) {
            return ToolResponse.error("error: the request names no scope; send the " + scopeHeader + " header");
        }
        McpToolEndpoint.Result result = new McpToolEndpoint(scopes::runnerOf).call(scope, tool,
                new JSONObject(arguments == null ? Map.of() : arguments).toString());
        return result.isError() ? ToolResponse.error(result.text()) : ToolResponse.success(result.text());
    }

    /** @return the scope the request being handled names in the scope header, or {@code null} when it names none */
    public String scopeOfCurrentRequest() {
        RoutingContext context = currentRequest.getCurrent();
        String value = context == null ? null : context.request().getHeader(scopeHeader);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
