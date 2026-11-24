package com.scivicslab.chatui.mcp;

import java.util.function.BiPredicate;

/**
 * Whether a request to agent-connector-kernel's MCP server may go on, and as which scope (tasks/todo.md, Decision 12).
 * A plain object with no HTTP in it: the server's authentication mechanism hands it the scope header and the
 * {@code Authorization} header.
 *
 * <p>The answer depends on one setting per deployment, {@code agent-connector-kernel.mcp.require-key} (Decision 13):
 * <ul>
 * <li>{@code true}, the default: every request names a scope and carries that scope's key as
 * {@code Authorization: Bearer <key>}. Without a scope the answer is 400. An unknown scope, a missing key and a wrong
 * key all get the same 401, so a local process without a key cannot learn which scope names exist.</li>
 * <li>{@code false}, set by chat-ui alone: no key is asked for, and a request goes on as the scope it names, or as no
 * scope at all, in which case it is offered no tools. This is chat-ui's behaviour before the split.</li>
 * </ul>
 */
public final class McpAccess {

    /** The request header that names the scope, unless the deployment sets another. */
    public static final String DEFAULT_SCOPE_HEADER = "X-Agent-Connector-Scope";

    /** The answer for one request. */
    public sealed interface Decision permits Allow, Refuse {
    }

    /** @param scope the scope the request goes on as, or {@code null} for none (only when no key is required) */
    public record Allow(String scope) implements Decision {
    }

    /** @param status the HTTP status the request is refused with */
    public record Refuse(int status) implements Decision {
    }

    private final boolean requireKey;
    private final BiPredicate<String, String> keyOpens;

    /**
     * @param requireKey whether every request must carry its scope's key
     * @param keyOpens   whether a key opens a scope; {@code false} for a scope that does not exist
     */
    public McpAccess(boolean requireKey, BiPredicate<String, String> keyOpens) {
        this.requireKey = requireKey;
        this.keyOpens = keyOpens;
    }

    /** @return whether every request must carry its scope's key */
    public boolean requireKey() {
        return requireKey;
    }

    /**
     * @param scopeHeader   the scope header's value, or {@code null}
     * @param authorization the {@code Authorization} header, or {@code null}
     * @return the decision
     */
    public Decision decide(String scopeHeader, String authorization) {
        String scope = scopeHeader == null || scopeHeader.isBlank() ? null : scopeHeader.strip();
        if (!requireKey) return new Allow(scope);
        if (scope == null) return new Refuse(400);
        String key = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7).strip() : null;
        // An unknown scope, a missing key and a wrong key get the same answer.
        return key != null && !key.isEmpty() && keyOpens.test(scope, key) ? new Allow(scope) : new Refuse(401);
    }
}
