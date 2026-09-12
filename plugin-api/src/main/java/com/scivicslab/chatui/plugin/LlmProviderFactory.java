package com.scivicslab.chatui.plugin;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.LlmProvider;

import java.util.List;

/**
 * Makes providers of one kind for conversations ({@code ProviderAndToolPlugins_260912_oo01}).
 *
 * <p>A provider is created per conversation, with that conversation's working directory and tool
 * set, so what a plugin jar hands the body is not a provider but this factory. The body keeps one
 * factory per {@link #kind()} in its registry, and {@code POST .../chats/{chatId}/provider} names
 * the kind.</p>
 */
public interface LlmProviderFactory {

    /** @return the kind named in the REST body and reported by status, e.g. {@code claude} */
    String kind();

    /**
     * The entries the provider dropdown offers for this kind: one per tool set the kind can take.
     * The first entry is the default tool set when the REST body names none.
     *
     * @return at least one choice
     */
    List<ProviderChoice> choices();

    /**
     * @param ctx the conversation the provider is for
     * @return a new provider
     * @throws IllegalArgumentException when {@code ctx.toolSet()} is not one this kind can take
     */
    LlmProvider create(ProviderCreationContext ctx);

    /** @return the tool set used when the REST body names none */
    default ToolSet defaultToolSet() {
        return choices().get(0).toolSet();
    }

    /** @return whether this kind can be created with the given tool set */
    default boolean accepts(ToolSet toolSet) {
        return choices().stream().anyMatch(c -> c.toolSet() == toolSet);
    }
}
