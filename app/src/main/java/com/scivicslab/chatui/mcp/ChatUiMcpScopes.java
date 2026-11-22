package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.agent.ConversationToolCatalog;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationToolRunner;
import com.scivicslab.chatui.tool.ToolRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * chat-ui's conversations as the scopes of agent-connector-kernel's MCP server at {@code /mcp}
 * ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}; tasks/todo.md, Decision 12).
 *
 * <ul>
 * <li>The tools are the harness tools that do not drive a conversation, from {@link ConversationToolCatalog}, and the
 * plugin tools.</li>
 * <li>A scope's name is a conversation's qualified name {@code <project>/<chat>}, sent in {@code X-Chat-Ui-Conversation}
 * ({@code agent-connector-kernel.mcp.scope-header} in application.properties). Its runner is the one the conversation
 * last published.</li>
 * <li>chat-ui gives its conversations no key during the split (Decisions 3 and 13). It sets
 * {@code agent-connector-kernel.mcp.require-key=false}, so {@link #opens} is never asked.</li>
 * </ul>
 */
@ApplicationScoped
public class ChatUiMcpScopes implements McpScopes {

    @Inject
    ChatUiActorSystem chats;

    @Override
    public List<ToolCatalog.Spec> tools() {
        List<ToolCatalog.Spec> specs = new ArrayList<>();
        ConversationToolCatalog.builtInTools().values().stream().filter(s -> !s.drivesConversation()).forEach(specs::add);
        chats.getPluginRegistry().tools().forEach(t -> specs.add(ToolCatalog.ofPlugin(t)));
        return specs;
    }

    @Override
    public ToolRunner runnerOf(String name) {
        ConversationToolRunner runner = chats.toolRunnerOf(name);
        return runner == null ? null : runner.core();
    }

    @Override
    public boolean opens(String name, String key) {
        return false;
    }
}
