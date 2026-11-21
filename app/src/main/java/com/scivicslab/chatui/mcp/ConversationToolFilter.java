package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Keeps to the tools of the conversation a request names ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}): a tool
 * outside that conversation's {@code ToolSet} is neither listed nor called. A request that names no conversation, or
 * one that does not exist, is offered no tools. The MCP server applies this to {@code tools/list} and
 * {@code tools/call} alike.
 */
@ApplicationScoped
public class ConversationToolFilter implements ToolFilter {

    @Inject
    ChatUiActorSystem chats;

    @Inject
    CurrentVertxRequest currentRequest;

    @Override
    public boolean test(ToolInfo tool, McpConnection connection) {
        return ChatUiMcpServer.endpointOf(chats).offers(ChatUiMcpServer.conversationOf(currentRequest), tool.name());
    }
}
