package com.scivicslab.chatui.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 *
 * <p>Parsed with Jackson rather than org.json: Codex's {@code web_search} item carries two
 * {@code id} keys ({@code item_N} and {@code ws_...}), which org.json rejects as a duplicate and
 * Jackson resolves to the last one. Both events of a search carry the same pair, so the
 * {@code tool_use} and its {@code tool_result} still match.</p>
 */
public class CodexEventParser {

    private static final Logger logger = Logger.getLogger(CodexEventParser.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param jsonLine one line of {@code codex exec --json} output
     * @return the events on that line, in order; empty for a blank line or a line that is not an
     *         event; a single error event if the line is not JSON
     */
    public List<StreamEvent> parse(String jsonLine) {
        if (jsonLine == null || jsonLine.isBlank()) return List.of();
        String trimmed = jsonLine.trim();
        if (!trimmed.startsWith("{")) return List.of();
        JsonNode json;
        try {
            json = MAPPER.readTree(trimmed);
        } catch (Exception e) {
            return List.of(StreamEvent.error("Failed to parse JSON: " + e.getMessage()));
        }
        String type = text(json, "type", "");
        return switch (type) {
            case "thread.started" -> List.of(new StreamEvent("system", "Thread started",
                    text(json, "thread_id", null), -1, -1, false, trimmed));
            case "turn.started" -> List.of();
            case "item.started", "item.updated", "item.completed" -> parseItem(type, json.get("item"), trimmed);
            case "turn.completed" -> List.of(new StreamEvent("result", null, null, -1, -1, false, trimmed));
            case "turn.failed" -> {
                JsonNode err = json.get("error");
                String message = err != null && err.isObject() ? text(err, "message", "turn failed")
                        : text(json, "message", "turn failed");
                yield List.of(new StreamEvent("error", message, null, -1, -1, true, trimmed));
            }
            case "error" -> List.of(new StreamEvent("error", text(json, "message", "error"), null, -1, -1, true, trimmed));
            default -> {
                logger.fine(() -> "Unhandled codex event type: " + type);
                yield List.of();
            }
        };
    }

    private List<StreamEvent> parseItem(String eventType, JsonNode item, String rawJson) {
        if (item == null || !item.isObject()) return List.of();
        boolean completed = "item.completed".equals(eventType);
        boolean started = "item.started".equals(eventType);
        String id = text(item, "id", "");
        String itemType = text(item, "type", "");
        return switch (itemType) {
            case "agent_message" -> completed
                    ? List.of(new StreamEvent("assistant", text(item, "text", ""), null, -1, -1, false, rawJson))
                    : List.of();
            case "reasoning" -> completed
                    ? List.of(new StreamEvent("thinking", text(item, "text", ""), null, -1, -1, false, rawJson))
                    : List.of();
            case "command_execution" -> {
                if (started) {
                    yield List.of(StreamEvent.toolUse(id, "command_execution",
                            MAPPER.createObjectNode().put("command", text(item, "command", "")).toString(), rawJson));
                }
                if (completed) {
                    int exitCode = item.path("exit_code").asInt(0);
                    boolean failed = exitCode != 0 || "failed".equals(text(item, "status", ""));
                    String output = text(item, "aggregated_output", "");
                    yield List.of(StreamEvent.toolResult(id,
                            output + (exitCode != 0 ? "\n(exit code " + exitCode + ")" : ""), failed, rawJson));
                }
                yield List.of();
            }
            case "file_change" -> {
                if (!completed) yield List.of();
                JsonNode changes = item.get("changes");
                var input = MAPPER.createObjectNode();
                input.set("changes", changes == null ? MAPPER.createArrayNode() : changes);
                boolean failed = "failed".equals(text(item, "status", ""));
                yield List.of(StreamEvent.toolUse(id, "file_change", input.toString(), rawJson),
                        StreamEvent.toolResult(id, text(item, "status", "completed"), failed, rawJson));
            }
            case "mcp_tool_call" -> {
                String name = text(item, "server", "mcp") + "/" + text(item, "tool", "tool");
                if (started) {
                    JsonNode args = item.get("arguments");
                    yield List.of(StreamEvent.toolUse(id, name, args == null ? "{}" : args.toString(), rawJson));
                }
                if (completed) {
                    JsonNode error = item.get("error");
                    JsonNode result = item.get("result");
                    boolean failed = error != null && !error.isNull();
                    String out = failed ? error.toString() : (result == null || result.isNull() ? "" : result.toString());
                    yield List.of(StreamEvent.toolResult(id, out, failed, rawJson));
                }
                yield List.of();
            }
            case "web_search" -> {
                if (started) {
                    yield List.of(StreamEvent.toolUse(id, "web_search",
                            MAPPER.createObjectNode().put("query", text(item, "query", "")).toString(), rawJson));
                }
                if (completed) {
                    // The query is known only when the search completes; the result carries it,
                    // since Codex does not report what the search returned.
                    yield List.of(StreamEvent.toolResult(id, "searched: " + text(item, "query", ""), false, rawJson));
                }
                yield List.of();
            }
            case "error" -> List.of(new StreamEvent("error", text(item, "message", "error"), null, -1, -1, true, rawJson));
            default -> List.of();
        };
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return fallback;
        return v.isTextual() ? v.asText() : v.toString();
    }
}
