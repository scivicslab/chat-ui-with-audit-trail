package com.scivicslab.chatui.plugin;

import java.util.List;

/**
 * The entry point of one plugin jar ({@code ProviderAndToolPlugins_260912_oo01}).
 *
 * <p>The body finds implementations with {@link java.util.ServiceLoader} on the class loader it
 * loaded the jars named in {@code chat-ui.plugins} into, so a plugin jar lists its implementation
 * in {@code META-INF/services/com.scivicslab.chatui.plugin.ChatUiPlugin}. What a plugin provides
 * is read once at start-up: provider factories, keyed by kind, and conversation tools, keyed by
 * name.</p>
 */
public interface ChatUiPlugin {

    /** @return a short identifier shown in the start-up log and {@code GET /api/plugins} */
    String id();

    /** @return the provider kinds this plugin adds; empty if none */
    default List<LlmProviderFactory> providerFactories() {
        return List.of();
    }

    /** @return the conversation tools this plugin adds; empty if none */
    default List<ConversationTool> tools() {
        return List.of();
    }
}
