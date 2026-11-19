package com.scivicslab.chatui.openaicompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * The openai-compat provider's MCP client for one MCP server ({@code OpenAiCompatStandardToolCalling_261120_oo01}).
 *
 * <p>It sends stateless MCP requests (protocol {@value #PROTOCOL_VERSION}): {@code tools/list} to learn the tools
 * the model may call, and {@code tools/call} to run one. To chat-ui's own MCP server it names its conversation in
 * {@code X-Chat-Ui-Conversation}, so the server lists and runs that conversation's tools.</p>
 */
public final class McpToolClient {

    private static final Logger logger = Logger.getLogger(McpToolClient.class.getName());

    /** The MCP protocol version of the requests; the first one whose requests stand on their own. */
    public static final String PROTOCOL_VERSION = "2026-07-28";
    /** The header chat-ui's MCP server reads the conversation from. */
    public static final String CONVERSATION_HEADER = "X-Chat-Ui-Conversation";

    /** How long a tool may run before its call is given up: as long as a turn's longest tool runs. */
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(30);

    /**
     * What a {@code tools/call} returned.
     *
     * @param text    the text of the result's content, joined
     * @param isError whether the server marked it an error, or the call did not reach the tool
     */
    public record Result(String text, boolean isError) {}

    private final String endpoint;
    private final String conversation;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private int nextId = 1;

    /**
     * @param endpoint     the server's MCP endpoint, e.g. {@code http://127.0.0.1:28030/mcp}
     * @param conversation the conversation to name in {@value #CONVERSATION_HEADER}, e.g.
     *                     {@code project1/chat-01}; {@code null} names none
     */
    public McpToolClient(String endpoint, String conversation) {
        this.endpoint = endpoint;
        this.conversation = conversation;
    }

    /** @return the server's MCP endpoint */
    public String endpoint() {
        return endpoint;
    }

    /**
     * Lists the server's tools, every page of them.
     *
     * @return the tools in the server's order
     * @throws McpException when the server cannot be reached or answers with an error
     */
    public List<ToolDefinition> listTools() {
        List<ToolDefinition> tools = new ArrayList<>();
        String cursor = null;
        do {
            JSONObject params = new JSONObject();
            if (cursor != null) params.put("cursor", cursor);
            JSONObject result = request("tools/list", null, params, Duration.ofSeconds(30));
            JSONArray page = result.optJSONArray("tools");
            for (int i = 0; page != null && i < page.length(); i++) {
                JSONObject t = page.getJSONObject(i);
                JSONObject schema = t.optJSONObject("inputSchema");
                tools.add(new ToolDefinition(t.getString("name"), t.optString("description", ""),
                        (schema == null ? new JSONObject().put("type", "object") : schema).toString()));
            }
            cursor = result.optString("nextCursor", null);
            if (cursor != null && cursor.isEmpty()) cursor = null;
        } while (cursor != null);
        return tools;
    }

    /**
     * Runs one tool.
     *
     * @param name          the tool's name, as {@link #listTools()} gave it
     * @param argumentsJson the arguments as a JSON object text
     * @return the result; a call the server refused, or one that did not reach it, is an error result whose
     *         text says why
     */
    public Result callTool(String name, String argumentsJson) {
        JSONObject arguments;
        try {
            arguments = new JSONObject(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (JSONException e) {
            return new Result("error: the arguments are not a JSON object: " + e.getMessage(), true);
        }
        try {
            JSONObject result = request("tools/call", name,
                    new JSONObject().put("name", name).put("arguments", arguments), CALL_TIMEOUT);
            StringBuilder text = new StringBuilder();
            JSONArray content = result.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject part = content.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) {
                    if (text.length() > 0) text.append("\n");
                    text.append(part.optString("text", ""));
                }
            }
            return new Result(text.toString(), result.optBoolean("isError", false));
        } catch (McpException e) {
            return new Result("error: " + e.getMessage(), true);
        }
    }

    /** Sends one stateless request and returns its {@code result}. */
    private JSONObject request(String method, String name, JSONObject params, Duration timeout) {
        params.put("_meta", new JSONObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JSONObject()));
        JSONObject body = new JSONObject().put("jsonrpc", "2.0").put("id", nextId())
                .put("method", method).put("params", params);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Protocol-Version", PROTOCOL_VERSION)
                .header("Mcp-Method", method);
        if (name != null) b.header("Mcp-Name", name);
        if (conversation != null) b.header(CONVERSATION_HEADER, conversation);
        HttpResponse<String> response;
        try {
            response = http.send(b.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(method + " to " + endpoint + " was interrupted");
        } catch (Exception e) {
            throw new McpException(method + " to " + endpoint + " failed: " + e.getMessage());
        }
        JSONObject message = messageOf(response.body());
        if (message == null) {
            throw new McpException(method + " to " + endpoint + ": HTTP " + response.statusCode()
                    + " with no JSON-RPC answer: " + response.body());
        }
        JSONObject error = message.optJSONObject("error");
        if (error != null) {
            throw new McpException(error.optString("message", error.toString()));
        }
        JSONObject result = message.optJSONObject("result");
        if (result == null) throw new McpException(method + " to " + endpoint + ": the answer has no result");
        return result;
    }

    private synchronized int nextId() {
        return nextId++;
    }

    /**
     * The JSON-RPC answer in a response body: the body itself when the server answered with JSON, or the last
     * {@code data:} line that holds an answer when it answered with an event stream.
     *
     * @return the answer, or {@code null} when the body holds none
     */
    static JSONObject messageOf(String body) {
        if (body == null) return null;
        String trimmed = body.strip();
        if (trimmed.startsWith("{")) {
            try {
                return new JSONObject(trimmed);
            } catch (JSONException e) {
                return null;
            }
        }
        JSONObject answer = null;
        for (String line : trimmed.split("\n")) {
            if (!line.startsWith("data:")) continue;
            try {
                JSONObject o = new JSONObject(line.substring(5).strip());
                if (o.has("result") || o.has("error")) answer = o;
            } catch (JSONException e) {
                logger.fine(() -> "Unreadable event-stream line: " + line);
            }
        }
        return answer;
    }

    /** A request that did not come back with a result. */
    public static final class McpException extends RuntimeException {
        McpException(String message) {
            super(message);
        }
    }
}
