package com.scivicslab.chatui.harness;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Parses one stream-json line from Claude Code into {@link StreamEvent}s
 * ({@code CliHarnessProvider_260912_oo01}).
 *
 * <p>One line may hold several content blocks — text, thinking, tool_use — and each becomes its
 * own event, so a tool call the harness makes is never lost behind the text that accompanied it.
 * The tool_use block's whole input and the tool_result block's whole content are kept: they are
 * what the conversation records to the I/O log.</p>
 */
public class StreamEventParser {

    private static final Logger logger = Logger.getLogger(StreamEventParser.class.getName());

    /**
     * Parses a single JSON line from the CLI stream.
     *
     * @param jsonLine a raw JSON string from the CLI stdout
     * @return the events on that line, in order; empty if the line is blank; a single error event
     *         if the line is not JSON
     */
    public List<StreamEvent> parse(String jsonLine) {
        if (jsonLine == null || jsonLine.isBlank()) return List.of();
        try {
            JSONObject json = new JSONObject(jsonLine);
            String type = json.optString("type", "unknown");
            return switch (type) {
                case "system" -> List.of(parseSystem(json, jsonLine));
                case "assistant" -> parseAssistant(json, jsonLine);
                case "user" -> parseUser(json, jsonLine);
                case "result" -> List.of(parseResult(json, jsonLine));
                case "error" -> List.of(parseError(json, jsonLine));
                case "rate_limit_event" -> List.of(new StreamEvent("rate_limit_event", null, null, -1, -1, false, jsonLine));
                default -> {
                    logger.warning("Unknown CLI event type: " + type + " | " + jsonLine);
                    yield List.of(new StreamEvent(type, null, null, -1, -1, false, jsonLine));
                }
            };
        } catch (Exception e) {
            return List.of(StreamEvent.error("Failed to parse JSON: " + e.getMessage()));
        }
    }

    private StreamEvent parseSystem(JSONObject json, String rawJson) {
        String subtype = json.optString("subtype", "");
        String sessionId = json.optString("session_id", null);

        if ("permission_request".equals(subtype)) {
            return parsePermissionRequest(json, rawJson);
        }

        String model = json.optString("model", "");
        String content = "init".equals(subtype)
                ? "Session initialized" + (model.isEmpty() ? "" : " (model: " + model + ")")
                : json.optString("message", json.optString("content", subtype));
        return new StreamEvent("system", content, sessionId, -1, -1, false, rawJson);
    }

    private StreamEvent parsePermissionRequest(JSONObject json, String rawJson) {
        String toolUseId = json.optString("tool_use_id", UUID.randomUUID().toString());
        String toolName = json.optString("tool_name", "unknown tool");

        String message = json.optString("message", "");
        if (message.isBlank()) {
            JSONObject toolInput = json.optJSONObject("tool_input");
            if (toolInput != null && "Write".equals(toolName)) {
                message = "Allow Claude to write: " + toolInput.optString("file_path", "(unknown file)");
            } else if (toolInput != null && "Edit".equals(toolName)) {
                message = "Allow Claude to edit: " + toolInput.optString("file_path", "(unknown file)");
            } else if (toolInput != null && "Bash".equals(toolName)) {
                message = "Allow Claude to run: " + toolInput.optString("command", "(command)");
            } else {
                message = "Allow Claude to use the " + toolName + " tool?";
            }
        }

        List<String> options = new ArrayList<>();
        JSONArray optionsArray = json.optJSONArray("options");
        if (optionsArray != null && optionsArray.length() > 0) {
            for (int i = 0; i < optionsArray.length(); i++) {
                Object opt = optionsArray.opt(i);
                if (opt instanceof JSONObject optObj) {
                    options.add(optObj.optString("label", optObj.optString("value", opt.toString())));
                } else {
                    options.add(String.valueOf(opt));
                }
            }
        } else {
            options.add("Yes");
            options.add("Yes, don't ask again");
            options.add("No");
        }

        logger.info("Permission request for tool: " + toolName + " (id=" + toolUseId + ")");
        return StreamEvent.prompt(toolUseId, message, "permission", options, rawJson);
    }

    /**
     * One event per content block. Text and thinking blocks are each merged into one event of
     * their kind, so a message whose text arrived as several blocks still reads as one; tool_use
     * blocks stay separate, one call each.
     */
    private List<StreamEvent> parseAssistant(JSONObject json, String rawJson) {
        JSONObject message = json.optJSONObject("message");
        if (message == null) {
            return List.of(new StreamEvent("assistant", json.optString("content", ""), null, -1, -1, false, rawJson));
        }
        JSONArray contentArray = message.optJSONArray("content");
        if (contentArray == null || contentArray.length() == 0) {
            return List.of(new StreamEvent("assistant", "", null, -1, -1, false, rawJson));
        }

        StringBuilder textBuilder = new StringBuilder();
        StringBuilder thinkingBuilder = new StringBuilder();
        List<StreamEvent> toolEvents = new ArrayList<>();

        for (int i = 0; i < contentArray.length(); i++) {
            JSONObject block = contentArray.optJSONObject(i);
            if (block == null) continue;
            switch (block.optString("type", "")) {
                case "text" -> textBuilder.append(block.optString("text", ""));
                case "thinking" -> thinkingBuilder.append(block.optString("thinking", ""));
                case "tool_use" -> {
                    String toolName = block.optString("name", "");
                    if ("AskUserQuestion".equals(toolName)) {
                        toolEvents.add(parseEmbeddedAskUser(block, rawJson));
                    } else if ("ExitPlanMode".equals(toolName)) {
                        toolEvents.add(parseExitPlanMode(block, rawJson));
                    } else {
                        String id = block.optString("id", UUID.randomUUID().toString());
                        JSONObject input = block.optJSONObject("input");
                        toolEvents.add(StreamEvent.toolUse(id, toolName,
                                input == null ? "{}" : input.toString(), rawJson));
                    }
                }
                default -> { }
            }
        }

        List<StreamEvent> events = new ArrayList<>();
        if (!thinkingBuilder.isEmpty()) {
            events.add(new StreamEvent("thinking", thinkingBuilder.toString(), null, -1, -1, false, rawJson));
        }
        if (!textBuilder.isEmpty()) {
            events.add(new StreamEvent("assistant", textBuilder.toString(), null, -1, -1, false, rawJson));
        }
        events.addAll(toolEvents);
        if (events.isEmpty()) {
            events.add(new StreamEvent("assistant", "", null, -1, -1, false, rawJson));
        }
        return events;
    }

    /**
     * A {@code user} line is how the CLI reports tool results (and permission denials): one
     * {@code tool_result} block per call, each becoming its own event with its whole content.
     */
    private List<StreamEvent> parseUser(JSONObject json, String rawJson) {
        List<StreamEvent> events = new ArrayList<>();
        JSONObject message = json.optJSONObject("message");
        if (message != null) {
            JSONArray content = message.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject item = content.optJSONObject(i);
                    if (item != null && "tool_result".equals(item.optString("type"))) {
                        boolean isError = item.optBoolean("is_error", false);
                        String toolUseId = item.optString("tool_use_id", null);
                        events.add(StreamEvent.toolResult(toolUseId, toolResultText(item), isError, rawJson));
                    }
                }
            }
        }
        if (!events.isEmpty()) return events;
        // Older CLI versions: a top-level tool_use_result field, without an id to pair on.
        JSONObject toolResult = json.optJSONObject("tool_use_result");
        if (toolResult != null) {
            return List.of(StreamEvent.toolResult(null, toolResult.toString(), false, rawJson));
        }
        return List.of(new StreamEvent("tool_result", null, null, -1, -1, false, rawJson));
    }

    /**
     * The text of a tool_result block. Its {@code content} is either a string or an array of
     * content blocks, of which the text blocks are joined.
     */
    static String toolResultText(JSONObject toolResultBlock) {
        Object content = toolResultBlock.opt("content");
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof JSONArray arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject part = arr.optJSONObject(i);
                if (part == null) {
                    sb.append(String.valueOf(arr.opt(i)));
                } else if ("text".equals(part.optString("type"))) {
                    sb.append(part.optString("text", ""));
                } else {
                    sb.append(part.toString());
                }
                if (i < arr.length() - 1) sb.append('\n');
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    private StreamEvent parseResult(JSONObject json, String rawJson) {
        String sessionId = json.optString("session_id", null);
        double costUsd = json.optDouble("total_cost_usd", -1);
        long durationMs = json.optLong("duration_ms", -1);
        boolean isError = json.optBoolean("is_error", false);
        if (isError) {
            return new StreamEvent("error", json.optString("result", "Unknown error"),
                    sessionId, costUsd, durationMs, true, rawJson);
        }
        // Carry the final text so the dispatcher can fall back to it when the CLI emitted
        // no assistant event for this turn.
        String finalText = json.optString("result", "");
        return new StreamEvent("result", finalText.isEmpty() ? null : finalText,
                sessionId, costUsd, durationMs, false, rawJson);
    }

    private StreamEvent parseError(JSONObject json, String rawJson) {
        JSONObject errorObj = json.optJSONObject("error");
        if (errorObj != null) {
            return new StreamEvent("error", errorObj.optString("message", "Unknown error"), null, -1, -1, true, rawJson);
        }
        return new StreamEvent("error", json.optString("error", json.optString("message", "Unknown error")),
                null, -1, -1, true, rawJson);
    }

    /**
     * Parses an {@code ExitPlanMode} tool_use block into an interactive plan-approval prompt. In
     * stream-json mode the CLI does not block for approval — it auto-denies the tool with an
     * "Exit plan mode?" tool_result and ends the turn — so the plan text is surfaced as a prompt.
     */
    private StreamEvent parseExitPlanMode(JSONObject toolUseBlock, String rawJson) {
        String id = toolUseBlock.optString("id", UUID.randomUUID().toString());
        JSONObject input = toolUseBlock.optJSONObject("input");
        String plan = input != null
                ? input.optString("plan", input.optString("summary", ""))
                : "";
        if (plan.isBlank()) plan = "Claude has finished planning. Proceed with the plan?";
        List<String> options = new ArrayList<>();
        options.add("Yes, proceed");
        options.add("No, keep planning");
        logger.info("ExitPlanMode prompt (id=" + id + ")");
        return StreamEvent.prompt(id, plan, "exit_plan_mode", options, rawJson);
    }

    private StreamEvent parseEmbeddedAskUser(JSONObject toolUseBlock, String rawJson) {
        String id = toolUseBlock.optString("id", UUID.randomUUID().toString());
        JSONObject input = toolUseBlock.optJSONObject("input");
        if (input == null) return StreamEvent.prompt(id, "Question from LLM", "ask_user", new ArrayList<>(), rawJson);
        return parseAskUserQuestion(id, input, rawJson);
    }

    private StreamEvent parseAskUserQuestion(String id, JSONObject input, String rawJson) {
        JSONArray questionsArray = input.optJSONArray("questions");
        if (questionsArray != null && questionsArray.length() > 0) {
            JSONObject firstQ = questionsArray.optJSONObject(0);
            if (firstQ != null) return parseSingleQuestion(id, firstQ, rawJson);
        }
        return parseSingleQuestion(id, input, rawJson);
    }

    private StreamEvent parseSingleQuestion(String id, JSONObject qObj, String rawJson) {
        String question = qObj.optString("question", qObj.optString("message", "Question from LLM"));
        List<String> options = new ArrayList<>();
        JSONArray optionsArray = qObj.optJSONArray("options");
        if (optionsArray != null) {
            for (int i = 0; i < optionsArray.length(); i++) {
                Object opt = optionsArray.opt(i);
                if (opt instanceof JSONObject optObj) options.add(optObj.optString("label", optObj.toString()));
                else options.add(String.valueOf(opt));
            }
        }
        return StreamEvent.prompt(id, question, "ask_user", options, rawJson);
    }
}
