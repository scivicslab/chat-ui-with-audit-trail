package com.scivicslab.chatui.llm;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Decides what the LLM proxy does with one request ({@code LlmKeyInjectionProxy_261121_oo01}; tasks/todo.md stage 8).
 * A plain object with no HTTP server in it.
 *
 * <p>A harness is given {@code http://127.0.0.1:<port>/<scope>} as its LLM base URL and a throw-away key, so a request
 * arrives as {@code /<scope>/v1/...} with the key in {@code x-api-key} or {@code Authorization: Bearer}. An unknown
 * scope gets 404, a missing or wrong key 401. Otherwise the request goes to the upstream with the path after the
 * scope, the throw-away key removed, and the real credential in its place.</p>
 */
public final class LlmProxyRequests {

    /** Request headers never passed on: the key, and the ones the HTTP client sets itself. */
    static final Set<String> DROPPED = Set.of("x-api-key", "authorization", "host", "content-length", "connection",
            "expect", "upgrade", "keep-alive", "transfer-encoding", "proxy-connection");

    /** What to do with a request: refuse it, or send it on. */
    public sealed interface Decision permits Refuse, Forward {
    }

    /** @param status the HTTP status, @param message why */
    public record Refuse(int status, String message) implements Decision {
    }

    /** @param target where to send it, @param headers the headers to send, with the real credential */
    public record Forward(URI target, Map<String, String> headers) implements Decision {
    }

    private final Predicate<String> scopeExists;
    private final BiPredicate<String, String> keyOpens;
    private final URI upstream;
    private final String credentialHeader;
    private final String credential;

    /**
     * @param scopeExists      whether a scope is registered
     * @param keyOpens         whether a key is the scope's throw-away key
     * @param upstream         the LLM provider's base URL, e.g. {@code https://api.anthropic.com}
     * @param credentialHeader the header the provider reads the credential from, e.g. {@code x-api-key}
     * @param credential       the real credential; never handed to a harness
     */
    public LlmProxyRequests(Predicate<String> scopeExists, BiPredicate<String, String> keyOpens, URI upstream,
                            String credentialHeader, String credential) {
        this.scopeExists = scopeExists;
        this.keyOpens = keyOpens;
        this.upstream = upstream;
        this.credentialHeader = credentialHeader.toLowerCase(Locale.ROOT);
        this.credential = credential;
    }

    /**
     * @param path    the request path with its query, e.g. {@code /s1/v1/messages?beta=true}
     * @param headers the request headers, any case
     * @return the decision
     */
    public Decision decide(String path, Map<String, List<String>> headers) {
        String rest = path == null || !path.startsWith("/") ? "" : path.substring(1);
        int slash = rest.indexOf('/');
        String scope = slash < 0 ? rest : rest.substring(0, slash);
        if (scope.isEmpty() || !scopeExists.test(scope)) return new Refuse(404, "no such scope: " + scope);
        Map<String, String> lower = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()) lower.put(name.toLowerCase(Locale.ROOT), values.get(0));
        });
        String key = lower.get("x-api-key");
        String auth = lower.get("authorization");
        if (key == null && auth != null && auth.startsWith("Bearer ")) key = auth.substring(7).strip();
        if (!keyOpens.test(scope, key)) return new Refuse(401, "the request does not carry this scope's key");
        Map<String, String> sent = new LinkedHashMap<>();
        lower.forEach((name, value) -> {
            if (!DROPPED.contains(name)) sent.put(name, value);
        });
        sent.put(credentialHeader, credential);
        String base = upstream.toString().replaceAll("/+$", "");
        return new Forward(URI.create(base + (slash < 0 ? "/" : rest.substring(slash))), sent);
    }
}
