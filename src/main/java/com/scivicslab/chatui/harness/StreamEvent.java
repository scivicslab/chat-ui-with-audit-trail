package com.scivicslab.chatui.harness;

import java.util.List;

/**
 * One parsed event from a CLI harness's stream-json output ({@code CliHarnessProvider_260912_oo01}).
 *
 * @param type       the event type ("assistant", "thinking", "tool_use", "tool_result", "system",
 *                   "result", "error", "prompt", ...)
 * @param content    the text content of the event, may be {@code null}; for {@code tool_use} the
 *                   tool's input as JSON text, for {@code tool_result} the result text
 * @param sessionId  the CLI session id, when the event carries one
 * @param costUsd    the accumulated cost in USD, or {@code -1} if unavailable
 * @param durationMs the turn duration in milliseconds, or {@code -1} if unavailable
 * @param isError    whether this event represents an error (or a failed tool result)
 * @param rawJson    the original JSON line from the CLI output
 * @param promptId   identifier of an interactive prompt, for {@code prompt} events
 * @param promptType kind of interactive prompt ("permission", "ask_user", "exit_plan_mode")
 * @param options    selectable answers of an interactive prompt
 * @param toolName   the harness's tool name, for {@code tool_use} events
 * @param toolUseId  identifier pairing a {@code tool_use} with its {@code tool_result}
 */
public record StreamEvent(
    String type,
    String content,
    String sessionId,
    double costUsd,
    long durationMs,
    boolean isError,
    String rawJson,
    String promptId,
    String promptType,
    List<String> options,
    String toolName,
    String toolUseId
) {

    /** Plain event without prompt or tool fields. */
    public StreamEvent(String type, String content, String sessionId,
                       double costUsd, long durationMs, boolean isError, String rawJson) {
        this(type, content, sessionId, costUsd, durationMs, isError, rawJson, null, null, null, null, null);
    }

    /** Text event of the given type with no session, cost or raw line. */
    public static StreamEvent text(String type, String content) {
        return new StreamEvent(type, content, null, -1, -1, false, null);
    }

    /** Turn-end event. */
    public static StreamEvent result(String sessionId, double costUsd, long durationMs) {
        return new StreamEvent("result", null, sessionId, costUsd, durationMs, false, null);
    }

    /** Error event with the given message. */
    public static StreamEvent error(String message) {
        return new StreamEvent("error", message, null, -1, -1, true, null);
    }

    /** Interactive prompt (permission dialog, question to the user, plan approval). */
    public static StreamEvent prompt(String promptId, String content,
                                     String promptType, List<String> options, String rawJson) {
        return new StreamEvent("prompt", content, null, -1, -1, false, rawJson,
                               promptId, promptType, options, null, null);
    }

    /** A tool call the harness made inside the turn. */
    public static StreamEvent toolUse(String toolUseId, String toolName, String inputJson, String rawJson) {
        return new StreamEvent("tool_use", inputJson, null, -1, -1, false, rawJson,
                               null, null, null, toolName, toolUseId);
    }

    /** The harness's result for an earlier {@link #toolUse}. */
    public static StreamEvent toolResult(String toolUseId, String content, boolean isError, String rawJson) {
        return new StreamEvent("tool_result", content, null, -1, -1, isError, rawJson,
                               null, null, null, null, toolUseId);
    }

    /** @return {@code true} if {@code content} is non-null and non-empty */
    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }

    /** @return {@code true} if this is an interactive prompt */
    public boolean isPrompt() {
        return "prompt".equals(type);
    }
}
