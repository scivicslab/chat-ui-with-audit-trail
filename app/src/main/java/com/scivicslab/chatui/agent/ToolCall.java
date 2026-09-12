package com.scivicslab.chatui.agent;

/**
 * A tool-call request parsed out of an LLM response by {@link TextToolCallParser}.
 *
 * <p>Local replacement for {@code quarkus-chat-ui3}'s {@code VllmResponse.ToolCall} — that type
 * carries chat-ui3's native-{@code tool_calls} machinery, which {@code chat-ui-with-audit-trail}
 * does not use (see {@code ChatSessionAgentLoop_260823_oo01}: tool calls are detected by parsing
 * response text, never via the LLM API's native {@code tool_calls} field).
 *
 * @param id            synthetic id ({@code text-call-0}, {@code text-call-1}, …), unique within one step
 * @param name           the tool name (e.g. {@code read}, {@code calc})
 * @param argumentsJson  the extracted parameters as a JSON object string
 */
public record ToolCall(String id, String name, String argumentsJson) {}
