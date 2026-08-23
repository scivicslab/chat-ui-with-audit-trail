package com.scivicslab.chatui.openaicompat.client;

import com.scivicslab.chatui.openaicompat.ToolDefinition;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Stateless HTTP client for OpenAI-compatible LLM APIs (vLLM, Ollama, NemoClaw, etc.).
 *
 * <p>Conversation history is managed by the caller. This client only handles
 * HTTP communication and SSE stream parsing.</p>
 */
public class OpenAiCompatClient {

    private static final Logger logger = Logger.getLogger(OpenAiCompatClient.class.getName());

    private final String baseUrl;
    private final HttpClient httpClient;
    private volatile List<String> cachedModels = List.of();
    private volatile Map<String, Integer> cachedMaxModelLen = Map.of();

    public interface StreamCallback {
        void onDelta(String content);
        void onComplete(long durationMs);
        void onError(String message);
    }

    /**
     * Response from a non-streaming chat completion request.
     *
     * @param content      text content of the assistant message (may be {@code null} when tool_calls
     *                     are present)
     * @param toolCalls    list of tool calls requested by the model (empty when finish_reason is
     *                     {@code stop})
     * @param finishReason the value of {@code finish_reason} from the response ({@code stop},
     *                     {@code tool_calls}, or {@code error:…} on failure)
     */
    public record NonStreamingResponse(
            String content,
            List<ChatMessage.ToolCallRequest.ToolCall> toolCalls,
            String finishReason) {}

    /**
     * Creates a client targeting the given OpenAI-compatible server.
     *
     * @param baseUrl the server's base URL (e.g. {@code http://localhost:8000})
     */
    public OpenAiCompatClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    /**
     * Returns the base URL of this client's target server.
     *
     * @return the base URL
     */
    public String getBaseUrl() { return baseUrl; }

    /**
     * Checks whether this server advertises the given model (based on the last cached model list).
     *
     * @param model the model identifier to look up
     * @return {@code true} if the model was found in the cached list
     */
    public boolean servesModel(String model) { return cachedModels.contains(model); }

    /**
     * Returns the list of model identifiers from the last successful {@link #fetchModels()} call.
     *
     * @return cached model IDs, or an empty list if models have not been fetched yet
     */
    public List<String> getCachedModels() { return cachedModels; }

    /**
     * Returns the maximum context length for the given model, as reported by the server.
     *
     * @param model the model identifier
     * @return the maximum model length, or {@code -1} if unknown
     */
    public int getMaxModelLen(String model) { return cachedMaxModelLen.getOrDefault(model, -1); }

    /**
     * Fetches the list of available models from the server's {@code /v1/models} endpoint
     * and updates the internal cache.
     *
     * @return list of model identifiers, or an empty list if the request fails
     */
    public List<String> fetchModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/models"))
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();
            String body = response.body();
            this.cachedModels = parseModelIds(body);
            this.cachedMaxModelLen = parseMaxModelLens(body);
            return cachedModels;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            logger.fine(() -> baseUrl + " /v1/models unavailable: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Sends a non-streaming chat completion request, optionally with tool definitions.
     *
     * <p>Used by the agent loop for intermediate turns where tool call parsing is needed.
     * The final user-visible turn may also use this method; callers emit the response content
     * as a single delta event.</p>
     *
     * @param model     the model identifier
     * @param messages  conversation history
     * @param noThink   if {@code true}, disables the model's thinking/CoT mode
     * @param maxTokens maximum tokens to generate (0 for server default)
     * @param tools     tool definitions to include in the request; empty list for no tools
     * @return a {@link NonStreamingResponse} — never {@code null}; errors are represented as
     *         {@code finishReason} starting with {@code "error:"}
     */
    public NonStreamingResponse sendNonStreaming(String model, List<ChatMessage> messages,
                                                 boolean noThink, int maxTokens,
                                                 List<ToolDefinition> tools) {
        try {
            String requestBody = buildRequestBody(model, messages, noThink, maxTokens, false, tools);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofMinutes(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new NonStreamingResponse(null, List.of(), "error:" + response.statusCode());
            }
            return parseNonStreamingResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new NonStreamingResponse("Cancelled", List.of(), "error:interrupted");
        } catch (Exception e) {
            logger.warning("Non-streaming request failed: " + e.getMessage());
            return new NonStreamingResponse("Error: " + e.getMessage(), List.of(), "error:exception");
        }
    }

    /**
     * Sends a streaming chat completion request to the server.
     *
     * @param model     the model identifier to use
     * @param history   the conversation history to include as context
     * @param noThink   if {@code true}, disables the model's thinking/chain-of-thought mode
     * @param maxTokens maximum number of tokens to generate (0 for server default)
     * @param callback  receives streamed deltas, completion, and error events
     * @return the full response text, or {@code null} if the request failed or was cancelled
     * @throws ContextLengthExceededException if the prompt exceeds the model's context window
     */
    public String sendPrompt(String model, List<ChatMessage> history, boolean noThink,
                             int maxTokens, StreamCallback callback) {
        long startTime = System.currentTimeMillis();
        try {
            String requestBody = buildRequestBody(model, history, noThink, maxTokens);
            HttpResponse<Stream<String>> response = sendWithRetry(requestBody);

            if (response.statusCode() == 400) {
                StringBuilder errorBody = new StringBuilder();
                response.body().forEach(errorBody::append);
                String errorStr = errorBody.toString();
                if (isContextLengthError(errorStr)) throw new ContextLengthExceededException(errorStr);
                callback.onError("HTTP 400: " + errorStr);
                return null;
            }
            if (response.statusCode() != 200) {
                StringBuilder errorBody = new StringBuilder();
                response.body().forEach(errorBody::append);
                callback.onError("HTTP " + response.statusCode() + ": " + errorBody);
                return null;
            }

            StringBuilder fullResponse = new StringBuilder();
            boolean interrupted = false;
            var iterator = response.body().iterator();
            while (iterator.hasNext()) {
                if (Thread.currentThread().isInterrupted()) { interrupted = true; break; }
                String content = parseSseLine(iterator.next());
                if (content != null) { fullResponse.append(content); callback.onDelta(content); }
            }
            if (interrupted) { callback.onError("Request cancelled"); return null; }
            callback.onComplete(System.currentTimeMillis() - startTime);
            return fullResponse.length() > 0 ? fullResponse.toString() : null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.onError("Request cancelled");
            return null;
        } catch (java.io.UncheckedIOException e) {
            if (e.getCause() != null && e.getCause().getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                callback.onError("Request cancelled");
                return null;
            }
            String msg = e.getMessage();
            if (isContextLengthError(msg)) throw new ContextLengthExceededException(msg);
            callback.onError("Error (" + baseUrl + "): " + msg);
            return null;
        } catch (ContextLengthExceededException e) {
            throw e;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Request failed", e);
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (isContextLengthError(detail)) throw new ContextLengthExceededException(detail);
            callback.onError("Error (" + baseUrl + "): " + detail);
            return null;
        }
    }

    private HttpResponse<Stream<String>> sendWithRetry(String requestBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Connection reset") || msg.contains("received no bytes"))) {
                logger.info("Connection reset, retrying");
                return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                        .send(request, HttpResponse.BodyHandlers.ofLines());
            }
            throw e;
        }
    }

    /** Builds a streaming request body with no tools (backward-compatible). */
    static String buildRequestBody(String model, List<ChatMessage> messages,
                                   boolean noThink, int maxTokens) {
        return buildRequestBody(model, messages, noThink, maxTokens, true, List.of());
    }

    /**
     * Builds a chat completion request body.
     *
     * @param stream whether to request SSE streaming ({@code stream:true/false})
     * @param tools  tool definitions to include; empty list omits the {@code tools} field
     */
    static String buildRequestBody(String model, List<ChatMessage> messages,
                                   boolean noThink, int maxTokens,
                                   boolean stream, List<ToolDefinition> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(escapeJson(model))
          .append("\",\"stream\":").append(stream).append(",");
        if (maxTokens > 0) sb.append("\"max_tokens\":").append(maxTokens).append(",");
        if (noThink) sb.append("\"chat_template_kwargs\":{\"enable_thinking\":false},");
        if (!tools.isEmpty()) {
            sb.append("\"tools\":[");
            for (int i = 0; i < tools.size(); i++) {
                if (i > 0) sb.append(",");
                ToolDefinition t = tools.get(i);
                sb.append("{\"type\":\"function\",\"function\":{")
                  .append("\"name\":\"").append(escapeJson(t.name())).append("\",")
                  .append("\"description\":\"").append(escapeJson(t.description())).append("\",")
                  .append("\"parameters\":").append(t.parametersJson())
                  .append("}}");
            }
            sb.append("],\"tool_choice\":\"auto\",");
        }
        sb.append("\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) sb.append(",");
            ChatMessage msg = messages.get(i);
            sb.append("{\"role\":\"").append(escapeJson(msg.role())).append("\",");
            switch (msg) {
                case ChatMessage.User u when u.hasImages() -> {
                    sb.append("\"content\":[");
                    for (int j = 0; j < u.imageDataUrls().size(); j++) {
                        if (j > 0) sb.append(",");
                        sb.append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"")
                          .append(escapeJson(u.imageDataUrls().get(j))).append("\"}}");
                    }
                    sb.append(",{\"type\":\"text\",\"text\":\"").append(escapeJson(u.content())).append("\"}]");
                }
                case ChatMessage.User u ->
                    sb.append("\"content\":\"").append(escapeJson(u.content())).append("\"");
                case ChatMessage.Assistant a ->
                    sb.append("\"content\":\"").append(escapeJson(a.content())).append("\"");
                case ChatMessage.ToolCallRequest t -> {
                    sb.append("\"content\":null,\"tool_calls\":[");
                    for (int j = 0; j < t.toolCalls().size(); j++) {
                        if (j > 0) sb.append(",");
                        var tc = t.toolCalls().get(j);
                        sb.append("{\"id\":\"").append(escapeJson(tc.id())).append("\",")
                          .append("\"type\":\"function\",\"function\":{")
                          .append("\"name\":\"").append(escapeJson(tc.name())).append("\",")
                          .append("\"arguments\":\"").append(escapeJson(tc.arguments())).append("\"}}");
                    }
                    sb.append("]");
                }
                case ChatMessage.ToolResult r ->
                    sb.append("\"tool_call_id\":\"").append(escapeJson(r.toolCallId())).append("\",")
                      .append("\"name\":\"").append(escapeJson(r.toolName())).append("\",")
                      .append("\"content\":\"").append(escapeJson(r.content())).append("\"");
                case ChatMessage.System s ->
                    sb.append("\"content\":\"").append(escapeJson(s.content())).append("\"");
            }
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    static NonStreamingResponse parseNonStreamingResponse(String json) {
        String finishReason = parseNsFinishReason(json);
        String content = parseNsContent(json);
        List<ChatMessage.ToolCallRequest.ToolCall> toolCalls = parseNsToolCalls(json);
        return new NonStreamingResponse(content, toolCalls, finishReason);
    }

    static String parseNsFinishReason(String json) {
        String marker = "\"finish_reason\":\"";
        int idx = json.indexOf(marker);
        if (idx < 0) return "stop";
        String value = unescapeJsonString(json, idx + marker.length());
        return value != null && !value.isEmpty() ? value : "stop";
    }

    static String parseNsContent(String json) {
        int msgIdx = json.indexOf("\"message\":");
        if (msgIdx < 0) return null;
        String marker = "\"content\":";
        int contentIdx = json.indexOf(marker, msgIdx);
        if (contentIdx < 0) return null;
        int pos = contentIdx + marker.length();
        while (pos < json.length() && json.charAt(pos) == ' ') pos++;
        if (pos >= json.length()) return null;
        char c = json.charAt(pos);
        if (c == 'n') return null; // null
        if (c == '"') return unescapeJsonString(json, pos + 1);
        return null;
    }

    static List<ChatMessage.ToolCallRequest.ToolCall> parseNsToolCalls(String json) {
        List<ChatMessage.ToolCallRequest.ToolCall> result = new ArrayList<>();
        int tcIdx = json.indexOf("\"tool_calls\":");
        if (tcIdx < 0) return result;
        int arrStart = json.indexOf('[', tcIdx);
        if (arrStart < 0) return result;
        int i = arrStart + 1;
        while (i < json.length()) {
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length() || json.charAt(i) == ']') break;
            if (json.charAt(i) == '{') {
                int objEnd = findMatchingBrace(json, i);
                if (objEnd < 0) break;
                String obj = json.substring(i, objEnd + 1);
                String id = extractFirstString(obj, "\"id\":\"");
                String name = null, arguments = null;
                int funcIdx = obj.indexOf("\"function\":");
                if (funcIdx >= 0) {
                    String funcPart = obj.substring(funcIdx);
                    name = extractFirstString(funcPart, "\"name\":\"");
                    arguments = extractFirstString(funcPart, "\"arguments\":\"");
                }
                if (id != null && name != null) {
                    result.add(new ChatMessage.ToolCallRequest.ToolCall(
                            id, name, arguments != null ? arguments : "{}"));
                }
                i = objEnd + 1;
            } else {
                i++;
            }
            while (i < json.length() && (json.charAt(i) == ',' || Character.isWhitespace(json.charAt(i)))) i++;
        }
        return result;
    }

    private static int findMatchingBrace(String json, int start) {
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') { depth++; }
            else if (c == '}') { depth--; if (depth == 0) return i; }
            else if (c == '"') {
                i++;
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') i++;
                    i++;
                }
            }
        }
        return -1;
    }

    private static String extractFirstString(String json, String marker) {
        int idx = json.indexOf(marker);
        if (idx < 0) return null;
        return unescapeJsonString(json, idx + marker.length());
    }

    static String parseSseLine(String line) {
        if (line == null || !line.startsWith("data: ")) return null;
        String data = line.substring(6).trim();
        if (data.equals("[DONE]")) return null;
        return extractDeltaContent(data);
    }

    static String extractDeltaContent(String json) {
        int deltaIdx = json.indexOf("\"delta\"");
        if (deltaIdx < 0) return null;
        String marker = "\"content\":\"";
        int contentIdx = json.indexOf(marker, deltaIdx);
        if (contentIdx < 0) return null;
        return unescapeJsonString(json, contentIdx + marker.length());
    }

    static boolean isContextLengthError(String body) {
        return body != null && (body.contains("input_tokens")
                || body.contains("context length") || body.contains("maximum input length"));
    }

    static List<String> parseModelIds(String json) {
        List<String> ids = new ArrayList<>();
        int dataIdx = json.indexOf("\"data\"");
        if (dataIdx < 0) return ids;
        int arrStart = json.indexOf('[', dataIdx);
        if (arrStart < 0) return ids;
        String marker = "\"id\":\"";
        int depth = 0, i = arrStart;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '[' || c == '{') { depth++; i++; }
            else if (c == ']' || c == '}') { depth--; if (depth <= 0) break; i++; }
            else if (c == '"') {
                if (depth == 2 && json.startsWith(marker, i)) {
                    int start = i + marker.length();
                    String value = unescapeJsonString(json, start);
                    if (value != null && !value.isEmpty()) ids.add(value);
                    i = start;
                    while (i < json.length() && json.charAt(i) != '"') { if (json.charAt(i) == '\\') i++; i++; }
                    i++;
                } else {
                    i++;
                    while (i < json.length() && json.charAt(i) != '"') { if (json.charAt(i) == '\\') i++; i++; }
                    i++;
                }
            } else i++;
        }
        return ids;
    }

    static Map<String, Integer> parseMaxModelLens(String json) {
        Map<String, Integer> result = new HashMap<>();
        int dataIdx = json.indexOf("\"data\"");
        if (dataIdx < 0) return result;
        int arrStart = json.indexOf('[', dataIdx);
        if (arrStart < 0) return result;
        int i = arrStart;
        while (i < json.length()) {
            int objStart = json.indexOf('{', i);
            if (objStart < 0) break;
            int depth = 0, objEnd = objStart;
            for (int j = objStart; j < json.length(); j++) {
                char c = json.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) { objEnd = j; break; } }
            }
            if (objEnd <= objStart) break;
            String obj = json.substring(objStart, objEnd + 1);
            int idIdx = obj.indexOf("\"id\":\"");
            String id = null;
            if (idIdx >= 0) id = unescapeJsonString(obj, idIdx + 6);
            int lenIdx = obj.indexOf("\"max_model_len\":");
            if (id != null && lenIdx >= 0) {
                int numStart = lenIdx + 16, numEnd = numStart;
                while (numEnd < obj.length() && Character.isDigit(obj.charAt(numEnd))) numEnd++;
                if (numEnd > numStart) {
                    try { result.put(id, Integer.parseInt(obj.substring(numStart, numEnd))); }
                    catch (NumberFormatException ignored) {}
                }
            }
            i = objEnd + 1;
        }
        return result;
    }

    static String unescapeJsonString(String json, int startIdx) {
        StringBuilder result = new StringBuilder();
        int i = startIdx;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') return result.toString();
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'u' -> {
                        if (i + 5 < json.length()) {
                            try { result.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16)); }
                            catch (NumberFormatException e) { result.append("\\u").append(json, i + 2, i + 6); }
                            i += 4;
                        }
                    }
                    default -> { result.append('\\'); result.append(next); }
                }
                i += 2;
            } else { result.append(c); i++; }
        }
        return result.toString();
    }

    static String escapeJson(String str) {
        if (str == null) return "";
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.toString();
    }
}
