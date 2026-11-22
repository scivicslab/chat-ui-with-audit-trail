package com.scivicslab.chatui.core.plugin;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.openaicompat.McpToolClient;
import com.scivicslab.chatui.openaicompat.OpenAiCompatProvider;
import com.scivicslab.chatui.plugin.LlmProviderFactory;
import com.scivicslab.chatui.plugin.ProviderChoice;
import com.scivicslab.chatui.plugin.ProviderCreationContext;

import java.util.List;

/**
 * The body's own provider kind: an OpenAI-compatible server, normally the local gpu-broker
 * ({@code ProviderAndToolPlugins_260912_oo01}). Registered before any plugin, so every instance
 * has it, and the only kind an instance started without plugins has.
 */
public class OpenAiCompatProviderFactory implements LlmProviderFactory {

    public static final String KIND = "openai-compat";

    private final List<String> servers;
    private final String defaultModel;

    public OpenAiCompatProviderFactory(List<String> servers, String defaultModel) {
        this.servers = servers;
        this.defaultModel = defaultModel;
    }

    @Override public String kind() { return KIND; }

    @Override
    public List<ProviderChoice> choices() {
        return List.of(new ProviderChoice(KIND, ToolSet.FULL, "Local LLM"));
    }

    @Override
    public LlmProvider create(ProviderCreationContext ctx) {
        if (ctx.toolSet() != ToolSet.FULL) {
            throw new IllegalArgumentException(KIND + " runs no tools of its own, so its tool set is always full");
        }
        // The tools that do not drive the conversation come from this instance's own MCP server, as tools
        // of this conversation (OpenAiCompatStandardToolCalling_261120_oo01).
        McpToolClient mcp = new McpToolClient("http://127.0.0.1:" + ctx.httpPort() + "/mcp",
                ChatUiActorSystem.chatActorName(ctx.projectId(), ctx.chatId()));
        OpenAiCompatProvider provider = new OpenAiCompatProvider(servers, defaultModel, mcp);
        // The most tokens a reply may run to; the provider's own default when it is not set.
        org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("chat-ui.max-reply-tokens", Integer.class)
                .ifPresent(provider::setMaxReplyTokens);
        return provider;
    }
}
