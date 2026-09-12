package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.plugin.ChatUiPlugin;
import com.scivicslab.chatui.plugin.LlmProviderFactory;
import com.scivicslab.chatui.plugin.ProviderChoice;
import com.scivicslab.chatui.plugin.ProviderCreationContext;

import java.nio.file.Path;
import java.util.List;

/**
 * The plugin that adds Claude Code and Codex as provider kinds
 * ({@code ProviderAndToolPlugins_260912_oo01}). An instance started without this jar has no code
 * that starts either CLI; one started with it offers them in the provider dropdown.
 *
 * <p>The settings every harness provider shares are read from the body's configuration through
 * the creation context, under the keys {@code CliHarnessProvider_260912_oo01} defined:
 * {@code chat-ui.harness.permission-mode}, {@code chat-ui.harness.session-dir},
 * {@code chat-ui.harness.claude-model}, {@code chat-ui.harness.codex-model}.</p>
 */
public class HarnessPlugin implements ChatUiPlugin {

    @Override public String id() { return "harness"; }

    @Override
    public List<LlmProviderFactory> providerFactories() {
        return List.of(new ClaudeCodeFactory(), new CodexFactory());
    }

    static HarnessSettings settings(ProviderCreationContext ctx) {
        Path sessionDir = Path.of(ctx.configOr("chat-ui.harness.session-dir",
                Path.of(System.getProperty("user.home"), ".chat-ui-with-audit-trail", "harness-sessions").toString()));
        return new HarnessSettings(
                ctx.configOr("chat-ui.harness.permission-mode", "bypassPermissions"),
                sessionDir,
                ctx.httpPort(),
                ctx.configOr("chat-ui.harness.claude-model", "sonnet"),
                ctx.configOr("chat-ui.harness.codex-model", "gpt-5.5"));
    }

    /** {@code claude}: with its own tools (collaboration set), or with them switched off (full set). */
    static final class ClaudeCodeFactory implements LlmProviderFactory {
        @Override public String kind() { return ClaudeCodeProvider.ID; }

        @Override
        public List<ProviderChoice> choices() {
            return List.of(
                new ProviderChoice(ClaudeCodeProvider.ID, ToolSet.COLLABORATION, "Claude Code"),
                new ProviderChoice(ClaudeCodeProvider.ID, ToolSet.FULL, "Claude Code (harness tools off)"));
        }

        @Override
        public LlmProvider create(ProviderCreationContext ctx) {
            return new ClaudeCodeProvider(settings(ctx), ctx.projectId(), ctx.chatId(), ctx.workingDir(), ctx.toolSet());
        }
    }

    /** {@code codex}: always with its own tools, since Codex cannot disable them. */
    static final class CodexFactory implements LlmProviderFactory {
        @Override public String kind() { return CodexProvider.ID; }

        @Override
        public List<ProviderChoice> choices() {
            return List.of(new ProviderChoice(CodexProvider.ID, ToolSet.COLLABORATION, "Codex"));
        }

        @Override
        public LlmProvider create(ProviderCreationContext ctx) {
            if (ctx.toolSet() != ToolSet.COLLABORATION) {
                throw new IllegalArgumentException("codex cannot disable its own tools, so its tool set is always collaboration");
            }
            return new CodexProvider(settings(ctx), ctx.projectId(), ctx.chatId(), ctx.workingDir());
        }
    }
}
