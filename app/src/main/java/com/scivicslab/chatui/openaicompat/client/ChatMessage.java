package com.scivicslab.chatui.openaicompat.client;

import java.util.List;

/**
 * Sealed interface representing a single message in the conversation history.
 */
public sealed interface ChatMessage {

    /**
     * Returns the role of this message in the conversation (e.g. "user", "assistant").
     *
     * @return the role string
     */
    String role();

    record User(String content, List<String> imageDataUrls) implements ChatMessage {
        /**
         * Creates a text-only user message with no images.
         *
         * @param content the user's text content
         */
        public User(String content) { this(content, List.of()); }
        /** {@inheritDoc} */
        @Override public String role() { return "user"; }

        /**
         * Checks whether this message contains any attached images.
         *
         * @return {@code true} if at least one image data URL is present
         */
        public boolean hasImages() { return imageDataUrls != null && !imageDataUrls.isEmpty(); }
    }

    record Assistant(String content) implements ChatMessage {
        @Override public String role() { return "assistant"; }
    }

    /** Assistant message that requests one or more tool calls instead of producing text. */
    record ToolCallRequest(List<ToolCall> toolCalls) implements ChatMessage {
        @Override public String role() { return "assistant"; }

        public record ToolCall(String id, String name, String arguments) {}
    }

    /** Tool execution result, sent back to the model after a {@link ToolCallRequest}. */
    record ToolResult(String toolCallId, String toolName, String content) implements ChatMessage {
        @Override public String role() { return "tool"; }
    }

    /** System-level instruction injected at the start of every request. */
    record System(String content) implements ChatMessage {
        @Override public String role() { return "system"; }
    }
}
