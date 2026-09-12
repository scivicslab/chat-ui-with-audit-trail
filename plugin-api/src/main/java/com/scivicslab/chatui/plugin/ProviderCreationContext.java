package com.scivicslab.chatui.plugin;

import com.scivicslab.chatui.agent.ToolSet;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a {@link LlmProviderFactory} is told about the conversation it creates a provider for
 * ({@code ProviderAndToolPlugins_260912_oo01}).
 *
 * @param projectId  owning project's id
 * @param chatId     conversation id within that project
 * @param workingDir the directory a harness works in, or {@code null} for this process's own
 * @param toolSet    the conversation's tool set
 * @param httpPort   this instance's HTTP port, for per-instance files
 * @param config     reads a configuration key, so a plugin's own keys ({@code chat-ui.harness.*})
 *                   need not be known to the body; empty when the key is unset
 */
public record ProviderCreationContext(
    String projectId,
    String chatId,
    Path workingDir,
    ToolSet toolSet,
    int httpPort,
    Function<String, Optional<String>> config
) {

    /** @return the configured value, or {@code fallback} when the key is unset or blank */
    public String configOr(String key, String fallback) {
        return config.apply(key).filter(v -> !v.isBlank()).orElse(fallback);
    }
}
