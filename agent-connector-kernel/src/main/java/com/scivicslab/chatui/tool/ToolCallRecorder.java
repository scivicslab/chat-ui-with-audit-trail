package com.scivicslab.chatui.tool;

/**
 * Records one tool call that came through MCP. The app writes it to the conversation's I/O log under the turn and
 * step the conversation is at; a recorder needs no turn or step of its own.
 */
@FunctionalInterface
public interface ToolCallRecorder {

    /** Records nothing. */
    ToolCallRecorder NONE = (tool, argumentsJson, observation) -> { };

    /**
     * @param tool          the tool's name
     * @param argumentsJson the call's arguments as a JSON object text
     * @param observation   what the call returned
     */
    void record(String tool, String argumentsJson, String observation);
}
