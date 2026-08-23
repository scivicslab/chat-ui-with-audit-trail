package com.scivicslab.chatui.openaicompat;

/**
 * A tool definition in OpenAI Function Calling format, ready to be embedded in a chat completion
 * request as an entry in the {@code tools} array.
 *
 * @param name           the function name (must match what the model will emit in {@code tool_calls})
 * @param description    human-readable description of what the tool does
 * @param parametersJson the JSON Schema for the tool's input parameters (raw JSON string)
 */
public record ToolDefinition(String name, String description, String parametersJson) {}
