package com.scivicslab.chatui.tool;

/**
 * Tools a {@link ToolRunner} runs on behalf of whoever built it, beyond the plain tools agent-connector-kernel
 * defines and the plugin tools. The app uses it for the tools that operate ConversationSquad actors.
 */
public interface ToolExtension {

    /** No extra tools. */
    ToolExtension NONE = new ToolExtension() {
        @Override public boolean defines(String tool) { return false; }
        @Override public boolean offers(String tool) { return false; }
        @Override public String run(String tool, String argumentsJson) { return null; }
    };

    /**
     * @return whether {@code tool} is one of the extension's own tools, offered or not. A name it defines is never
     *         taken for a plugin tool of the same name.
     */
    boolean defines(String tool);

    /** @return whether this extension runs {@code tool} and lets it be offered to MCP clients */
    boolean offers(String tool);

    /**
     * @param tool          the tool's name
     * @param argumentsJson the call's arguments as a JSON object text
     * @return the observation, or {@code null} when this extension does not run {@code tool}
     */
    String run(String tool, String argumentsJson);
}
