package com.scivicslab.chatui.core.rest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * SSE event DTO for chat communication.
 *
 * <p>Server to Client event types:</p>
 * <ul>
 *   <li>{@code delta} - Partial text content from the LLM</li>
 *   <li>{@code result} - Final result with session ID and cost info</li>
 *   <li>{@code error} - Error message</li>
 *   <li>{@code info} - Informational message (e.g., model changed)</li>
 *   <li>{@code mcp_user} - User message received via MCP (displayed like a user message with MCP attribution)</li>
 *   <li>{@code status} - Status update (model, session, busy state)</li>
 *   <li>{@code thinking} - Thinking/activity indicator</li>
 *   <li>{@code prompt} - Interactive prompt from the LLM (tool permission, yes/no, etc.)</li>
 *   <li>{@code heartbeat} - Keep-alive for SSE connection</li>
 *   <li>{@code log} - Server log entry (level, logger, message, timestamp)</li>
 *   <li>{@code tool_use} - A tool call the provider's own harness made inside one sendPrompt
 *       (CliHarnessProvider_260912_oo01): {@code toolName}, {@code toolUseId}, input JSON in
 *       {@code content}</li>
 *   <li>{@code tool_result} - The harness's result for a {@code tool_use}, paired by
 *       {@code toolUseId}; the result text in {@code content}, {@code isError} when it failed</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(
    String type,
    String content,
    String sessionId,
    Double costUsd,
    Long durationMs,
    String model,
    Boolean busy,
    String promptId,
    String promptType,
    List<String> options,
    String logLevel,
    String loggerName,
    Long timestamp,
    List<String> images,
    /** Tool name, on {@code tool_use} events. */
    String toolName,
    /** Identifier pairing a {@code tool_use} with its {@code tool_result}. */
    String toolUseId,
    /** Whether a {@code tool_result} reports a failure. */
    Boolean isError
) {

    /**
     * Creates a delta event carrying a partial text chunk from the LLM.
     *
     * @param content the partial text content
     * @return a new delta event
     */
    public static ChatEvent delta(String content) {
        return new ChatEvent("delta", content, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates a result event indicating that the LLM response is complete.
     *
     * @param sessionId the session identifier
     * @param costUsd   the estimated cost in USD
     * @param durationMs the response duration in milliseconds
     * @return a new result event
     */
    public static ChatEvent result(String sessionId, double costUsd, long durationMs) {
        return new ChatEvent("result", null, sessionId, costUsd, durationMs, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates a result event with additional model and busy-state information.
     *
     * @param sessionId  the session identifier
     * @param costUsd    the estimated cost in USD
     * @param durationMs the response duration in milliseconds
     * @param model      the model that produced the response
     * @param busy       whether the actor is still busy after this result
     * @return a new result event
     */
    public static ChatEvent result(String sessionId, double costUsd, long durationMs, String model, boolean busy) {
        return new ChatEvent("result", null, sessionId, costUsd, durationMs, model, busy, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates an error event with the given message.
     *
     * @param content the error message
     * @return a new error event
     */
    public static ChatEvent error(String content) {
        return new ChatEvent("error", content, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates an informational event (e.g., model changed, processing started).
     *
     * @param content the informational message
     * @return a new info event
     */
    public static ChatEvent info(String content) {
        return new ChatEvent("info", content, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates a user message event, displayed in the chat area as a user prompt.
     *
     * @param content the user's prompt text
     * @return a new user event
     */
    public static ChatEvent user(String content) {
        return user(content, null);
    }

    /**
     * Creates a user message event carrying attached images alongside the prompt text.
     *
     * @param content the user's prompt text
     * @param images  data URLs of images attached to this prompt, or {@code null}/empty if none
     * @return a new user event
     */
    public static ChatEvent user(String content, List<String> images) {
        return new ChatEvent("user", content, null, null, null, null, null, null, null, null, null, null, null,
                (images == null || images.isEmpty()) ? null : images, null, null, null);
    }

    /**
     * Creates an MCP user message event, displayed in the chat area like a user message
     * but with MCP sender attribution.
     *
     * @param content the message content (includes caller label and prompt text)
     * @return a new mcp_user event
     */
    public static ChatEvent mcpUser(String content) {
        return new ChatEvent("mcp_user", content, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates a status event reporting the current model, session, and busy state.
     *
     * @param model     the active model name
     * @param sessionId the current session identifier
     * @param busy      whether the actor is currently processing a request
     * @return a new status event
     */
    public static ChatEvent status(String model, String sessionId, boolean busy) {
        return new ChatEvent("status", null, sessionId, null, null, model, busy, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates a thinking event indicating LLM activity or reasoning progress.
     *
     * @param content the thinking/activity text
     * @return a new thinking event
     */
    public static ChatEvent thinking(String content) {
        return new ChatEvent("thinking", content, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates a heartbeat event used to keep the SSE connection alive.
     *
     * @return a new heartbeat event
     */
    public static ChatEvent heartbeat() {
        return new ChatEvent("heartbeat", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates a prompt event requesting interactive input from the client.
     *
     * @param promptId   unique identifier for this prompt so the client can reply
     * @param content    the prompt message displayed to the user
     * @param promptType the kind of prompt (e.g., "tool_permission", "yes_no")
     * @param options    available response options, or {@code null} for free-text
     * @return a new prompt event
     */
    public static ChatEvent prompt(String promptId, String content, String promptType, List<String> options) {
        return new ChatEvent("prompt", content, null, null, null, null, null,
                             promptId, promptType, options, null, null, null, null, null, null, null);
    }

    /**
     * Creates a log event forwarding a server-side log entry to the client.
     *
     * @param level   the log level (e.g., "INFO", "WARNING")
     * @param logger  the logger name that produced the entry
     * @param message the log message text
     * @param ts      the timestamp in epoch milliseconds
     * @return a new log event
     */
    public static ChatEvent log(String level, String logger, String message, long ts) {
        return new ChatEvent("log", message, null, null, null, null, null,
                             null, null, null, level, logger, ts, null, null, null, null);
    }

    /**
     * Creates a translation event displaying an English translation of the user's prompt.
     * Rendered in the UI with a green "EN: " prefix badge.
     *
     * @param content the English translation text
     * @return a new translation event
     */
    public static ChatEvent translation(String content) {
        return new ChatEvent("translation", content, null, null, null, null, null,
                             null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates a btw_delta event carrying a partial text chunk from a /btw side question response.
     *
     * @param content the partial text content
     * @return a new btw_delta event
     */
    public static ChatEvent btwDelta(String content) {
        return new ChatEvent("btw_delta", content, null, null, null, null, null,
                             null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates a btw_result event indicating that a /btw side question response is complete.
     *
     * @return a new btw_result event
     */
    public static ChatEvent btwResult() {
        return new ChatEvent("btw_result", null, null, null, null, null, null,
                             null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates a tool_use event: a tool call the provider's own harness (Claude Code, Codex) made
     * inside one {@code sendPrompt}, reported so the conversation can record it to the I/O log
     * ({@code CliHarnessProvider_260912_oo01}). The conversation did not make this call and does
     * not execute it; the harness does both.
     *
     * @param toolUseId identifier the matching {@link #toolResult} will carry
     * @param toolName  the harness's tool name (e.g. {@code Read})
     * @param inputJson the tool's input as a JSON object text
     * @return a new tool_use event
     */
    public static ChatEvent toolUse(String toolUseId, String toolName, String inputJson) {
        return new ChatEvent("tool_use", inputJson, null, null, null, null, null,
                             null, null, null, null, null, null, null, toolName, toolUseId, null);
    }

    /**
     * Creates a tool_result event: what the harness's tool returned for an earlier
     * {@link #toolUse} with the same {@code toolUseId}.
     *
     * @param toolUseId identifier of the tool_use this answers
     * @param content   the result text, whole
     * @param isError   whether the tool reported a failure
     * @return a new tool_result event
     */
    public static ChatEvent toolResult(String toolUseId, String content, boolean isError) {
        return new ChatEvent("tool_result", content, null, null, null, null, null,
                             null, null, null, null, null, null, null, null, toolUseId, isError);
    }
}
