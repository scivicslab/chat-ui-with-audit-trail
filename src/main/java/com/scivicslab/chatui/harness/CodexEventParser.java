package com.scivicslab.chatui.harness;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.logging.Logger;

/**
 * Parses one JSON Lines event from {@code codex exec --json} into {@link StreamEvent}s
 * ({@code CliHarnessProvider_260912_oo01}).
 *
 * <p>Codex reports a turn as {@code thread.started} (carrying the thread id that
 * {@code codex exec resume} continues), {@code turn.started}, a sequence of
 * {@code item.started}/{@code item.updated}/{@code item.completed} events, and
 * {@code turn.completed} or {@code turn.failed}. An item is the agent's message, its reasoning, or
 * one of its own tool calls — a command execution, a file change, an MCP tool call, a web search.
 * Tool calls are reported as a {@code tool_use} when the item starts and a {@code tool_result}
 * when it completes, paired by the item id, so the conversation records them as it records Claude
 * Code's.</p>
 */
public class CodexEventParser {

    private static final Logger logger = Logger.getLogger(CodexEventParser.class.getName());

    /**
     * @param jsonLine one line of {@code codex exec --json} output
     * @return the events on that line, in order; empty for a blank line or a line that is not an
     *         event; a single error event if the line is not JSON
     */
    public List<StreamEvent> parse(String jsonLine) {
        if (jsonLine == null || jsonLine.isBlank()) return List.of();
        String trimmed = jsonLine.trim();
        if (!trimmed.startsWith("{")) return List.of();
        JSONObject json;
        try {
            json = new JSONObject(trimmed);
        } catch (Exception e) {
            return List.of(StreamEvent.error("Failed to parse JSON: " + e.getMessage()));
        }
        String type = json.optString("type", "");
        return switch (type) {
            case "thread.started" -> List.of(new StreamEvent("system", "Thread started",
                    json.optString("thread_id", null), -1, -1, false, trimmed));
            case "turn.started" -> List.of();
            case "item.started", "item.updated", "item.completed" -> parseItem(type, json.optJSONObject("item"), trimmed);
            case "turn.completed" -> List.of(new StreamEvent("result", null, null, -1, -1, false, trimmed));
            case "turn.failed" -> {
                JSONObject err = json.optJSONObject("error");
                yield List.of(new StreamEvent("error",
                        err == null ? json.optString("message", "turn failed") : err.optString("message", "turn failed"),
                        null, -1, -1, true, trimmed));
            }
            case "error" -> List.of(new StreamEvent("error", json.optString("message", "error"), null, -1, -1, true, trimmed));
            default -> {
                logger.fine(() -> "Unhandled codex event type: " + type);
                yield List.of();
            }
        };
    }

    private List<StreamEvent> parseItem(String eventType, JSONObject item, String rawJson) {
        if (item == null) return List.of();
        boolean completed = "item.completed".equals(eventType);
        boolean started = "item.started".equals(eventType);
        String id = item.optString("id", "");
        String itemType = item.optString("type", "");
        return switch (itemType) {
            case "agent_message" -> completed
                    ? List.of(new StreamEvent("assistant", item.optString("text", ""), null, -1, -1, false, rawJson))
                    : List.of();
            case "reasoning" -> completed
                    ? List.of(new StreamEvent("thinking", item.optString("text", ""), null, -1, -1, false, rawJson))
                    : List.of();
            case "command_execution" -> {
                if (started) {
                    yield List.of(StreamEvent.toolUse(id, "command_execution",
                            new JSONObject().put("command", item.optString("command", "")).toString(), rawJson));
                }
                if (completed) {
                    int exitCode = item.optInt("exit_code", 0);
                    boolean failed = exitCode != 0 || "failed".equals(item.optString("status", ""));
                    String output = item.optString("aggregated_output", "");
                    yield List.of(StreamEvent.toolResult(id,
                            output + (exitCode != 0 ? "\n(exit code " + exitCode + ")" : ""), failed, rawJson));
                }
                yield List.of();
            }
            case "file_change" -> {
                if (!completed) yield List.of();
                JSONArray changes = item.optJSONArray("changes");
                String input = new JSONObject().put("changes", changes == null ? new JSONArray() : changes).toString();
                boolean failed = "failed".equals(item.optString("status", ""));
                yield List.of(StreamEvent.toolUse(id, "file_change", input, rawJson),
                        StreamEvent.toolResult(id, item.optString("status", "completed"), failed, rawJson));
            }
            case "mcp_tool_call" -> {
                String name = item.optString("server", "mcp") + "/" + item.optString("tool", "tool");
                if (started) {
                    Object args = item.opt("arguments");
                    yield List.of(StreamEvent.toolUse(id, name, args == null ? "{}" : args.toString(), rawJson));
                }
                if (completed) {
                    Object error = item.opt("error");
                    Object result = item.opt("result");
                    boolean failed = error != null && !JSONObject.NULL.equals(error);
                    String text = failed ? error.toString() : (result == null ? "" : result.toString());
                    yield List.of(StreamEvent.toolResult(id, text, failed, rawJson));
                }
                yield List.of();
            }
            case "web_search" -> {
                if (started) {
                    yield List.of(StreamEvent.toolUse(id, "web_search",
                            new JSONObject().put("query", item.optString("query", "")).toString(), rawJson));
                }
                if (completed) yield List.of(StreamEvent.toolResult(id, item.optString("query", ""), false, rawJson));
                yield List.of();
            }
            case "error" -> List.of(new StreamEvent("error", item.optString("message", "error"), null, -1, -1, true, rawJson));
            default -> List.of();
        };
    }
}
