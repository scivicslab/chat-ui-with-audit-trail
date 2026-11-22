package com.scivicslab.chatui.tool;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.plugin.ConversationTool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one scope's tool calls run with: in chat-ui a scope is a conversation.
 *
 * @param name        the scope's name, e.g. {@code "project1/chat-01"}
 * @param fileScope   the range of the file system its file tools may touch
 * @param toolSet     which tools it is shown and may call
 * @param pluginTools the plugin tools, by name, in the order they are listed
 */
public record ToolScope(String name, FileAccessScope fileScope, ToolSet toolSet, Map<String, ConversationTool> pluginTools) {

    public ToolScope {
        pluginTools = Collections.unmodifiableMap(new LinkedHashMap<>(pluginTools));
    }
}
