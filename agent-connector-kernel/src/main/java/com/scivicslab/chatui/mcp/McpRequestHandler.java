package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.tool.ToolRunner;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Answers one HTTP request to agent-connector-kernel's localhost MCP server (tasks/todo.md, Decisions 5 and stage 6).
 * A plain object with no HTTP in it: the server hands it the two headers and the body, and writes back what it returns.
 *
 * <p>A request names its scope in {@value #SCOPE_HEADER} and carries the scope's key as
 * {@code Authorization: Bearer <key>}. Without a scope the answer is 400. For a scope that does not exist and for a
 * request without the scope's key, the answer is the same 401, so a local process without a key cannot learn which
 * scope names exist. Only then is the body read as one JSON-RPC message: {@code initialize}, {@code tools/list},
 * {@code tools/call}, or a notification, which gets 202 and no body.</p>
 */
public final class McpRequestHandler {

    /** The request header that names the scope. */
    public static final String SCOPE_HEADER = "X-Agent-Connector-Scope";

    /**
     * An HTTP answer.
     *
     * @param status the HTTP status code
     * @param body   the JSON body, or empty for none
     */
    public record Reply(int status, String body) {
    }

    private final Predicate<String> scopeExists;
    private final BiFunction<String, String, ToolRunner> open;

    /**
     * @param scopeExists whether a scope of that name is registered
     * @param open        the runner of a scope when the key is its key, else {@code null}
     */
    public McpRequestHandler(Predicate<String> scopeExists, BiFunction<String, String, ToolRunner> open) {
        this.scopeExists = scopeExists;
        this.open = open;
    }

    /**
     * @param scope         the {@value #SCOPE_HEADER} header, or {@code null}
     * @param authorization the {@code Authorization} header, or {@code null}
     * @param body          the request body
     * @return the answer
     */
    public Reply handle(String scope, String authorization, String body) {
        if (scope == null || scope.isBlank()) return refusal(400, "the request names no scope; send " + SCOPE_HEADER);
        String name = scope.strip();
        String key = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7).strip() : null;
        // An unknown scope and a wrong key get the same answer, so the answer tells nothing about which scopes exist.
        ToolRunner runner = scopeExists.test(name) ? open.apply(name, key) : null;
        if (runner == null) return refusal(401, "the request does not carry this scope's key");

        JSONObject request;
        try {
            request = new JSONObject(body == null ? "" : body);
        } catch (Exception e) {
            return new Reply(400, rpcError(null, -32700, "parse error").toString());
        }
        Object id = request.opt("id");
        String method = request.optString("method", "");
        if (method.startsWith("notifications/")) return new Reply(202, "");
        McpToolEndpoint endpoint = new McpToolEndpoint(n -> n.equals(name) ? runner : null);
        JSONObject params = request.optJSONObject("params");
        if (params == null) params = new JSONObject();
        switch (method) {
            case "initialize": {
                JSONObject result = new JSONObject()
                        .put("protocolVersion", params.optString("protocolVersion", "2025-06-18"))
                        .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                        .put("serverInfo", new JSONObject().put("name", "agent-connector-kernel"));
                return new Reply(200, rpcResult(id, result).toString());
            }
            case "tools/list": {
                JSONArray tools = new JSONArray();
                for (ToolCatalog.Spec spec : endpoint.list(name)) {
                    tools.put(new JSONObject().put("name", spec.name()).put("description", spec.description())
                            .put("inputSchema", spec.inputSchema()));
                }
                return new Reply(200, rpcResult(id, new JSONObject().put("tools", tools)).toString());
            }
            case "tools/call": {
                String tool = params.optString("name", "");
                JSONObject arguments = params.optJSONObject("arguments");
                if (!endpoint.offers(name, tool)) {
                    return new Reply(200, rpcError(id, -32602, "Invalid tool name: " + tool).toString());
                }
                McpToolEndpoint.Result r = endpoint.call(name, tool, arguments == null ? "{}" : arguments.toString());
                JSONObject result = new JSONObject()
                        .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", r.text())))
                        .put("isError", r.isError());
                return new Reply(200, rpcResult(id, result).toString());
            }
            default:
                return new Reply(200, rpcError(id, -32601, "method not found: " + method).toString());
        }
    }

    private static Reply refusal(int status, String message) {
        return new Reply(status, new JSONObject().put("error", message).toString());
    }

    private static JSONObject rpcResult(Object id, JSONObject result) {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id).put("result", result);
    }

    private static JSONObject rpcError(Object id, int code, String message) {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id)
                .put("error", new JSONObject().put("code", code).put("message", message));
    }
}
