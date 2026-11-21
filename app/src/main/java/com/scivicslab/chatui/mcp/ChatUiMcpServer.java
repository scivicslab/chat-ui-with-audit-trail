package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.agent.ConversationToolCatalog;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationToolRunner;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * chat-ui's MCP server at {@code /mcp}: the harness tools that do not drive a conversation, run as the tools of the
 * conversation the request names ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}).
 *
 * <p>The tools are registered once at start-up from {@link ConversationToolCatalog} and the plugin registry. A request names its
 * conversation in the {@value #CONVERSATION_HEADER} header as {@code <project>/<chat>}; {@link ConversationToolFilter}
 * lists only that conversation's tools, and a call runs with that conversation's {@link ConversationToolRunner} on
 * the thread that took the request, without a message to the conversation's {@code ChatSession}.</p>
 */
@ApplicationScoped
public class ChatUiMcpServer {

    /** The request header that names the conversation, as {@code <project>/<chat>}. */
    public static final String CONVERSATION_HEADER = "X-Chat-Ui-Conversation";

    private static final Logger LOG = Logger.getLogger(ChatUiMcpServer.class.getName());

    @Inject
    ToolManager toolManager;

    @Inject
    ChatUiActorSystem chats;

    @Inject
    CurrentVertxRequest currentRequest;

    void register(@Observes StartupEvent event) {
        List<ToolCatalog.Spec> specs = new ArrayList<>();
        ConversationToolCatalog.builtInTools().values().stream().filter(s -> !s.drivesConversation()).forEach(specs::add);
        chats.getPluginRegistry().tools().forEach(t -> specs.add(ToolCatalog.ofPlugin(t)));
        for (ToolCatalog.Spec spec : specs) {
            toolManager.newTool(spec.name())
                    .setDescription(spec.description())
                    .setInputSchema(new JsonObject(spec.inputSchema().toString()))
                    .setHandler(arguments -> call(spec.name(), arguments.args()), true)
                    .register();
        }
        LOG.info("MCP server offers " + specs.size() + " tools at /mcp");
    }

    /** Runs one {@code tools/call}; an observation that starts with {@code error: } is returned as an error result. */
    private ToolResponse call(String tool, Map<String, Object> arguments) {
        String conversation = conversationOf(currentRequest);
        if (conversation == null) {
            return ToolResponse.error("error: the request names no conversation; send the " + CONVERSATION_HEADER
                    + " header with <project>/<chat>");
        }
        McpToolEndpoint.Result result = endpointOf(chats).call(conversation, tool,
                new JSONObject(arguments == null ? Map.of() : arguments).toString());
        return result.isError() ? ToolResponse.error(result.text()) : ToolResponse.success(result.text());
    }

    /**
     * @return agent-connector-kernel's MCP tool endpoint over this instance's conversations: a conversation's scope
     *         name is its qualified name, and its runner is the one the conversation last published
     */
    static McpToolEndpoint endpointOf(ChatUiActorSystem chats) {
        return new McpToolEndpoint(name -> {
            ConversationToolRunner runner = chats.toolRunnerOf(name);
            return runner == null ? null : runner.core();
        });
    }

    /**
     * @param currentRequest the HTTP request being handled
     * @return the conversation the request names in {@value #CONVERSATION_HEADER}, or {@code null} when it names none
     */
    static String conversationOf(CurrentVertxRequest currentRequest) {
        RoutingContext context = currentRequest.getCurrent();
        String value = context == null ? null : context.request().getHeader(CONVERSATION_HEADER);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
