package com.scivicslab.chatui.plugin;

import com.scivicslab.chatui.agent.ToolSet;

/**
 * One entry of the provider dropdown: a kind with a tool set, and the label the browser shows
 * ({@code ProviderAndToolPlugins_260912_oo01}).
 *
 * @param kind    the provider kind
 * @param toolSet the tool set the conversation gets with this choice
 * @param label   what the dropdown shows, e.g. {@code Claude (bare model)}
 */
public record ProviderChoice(String kind, ToolSet toolSet, String label) {

    /** @return the dropdown's value: {@code kind} alone for the kind's default tool set, else {@code kind:toolSet} */
    public String value(ToolSet defaultToolSet) {
        return toolSet == defaultToolSet ? kind : kind + ":" + toolSet.id();
    }
}
