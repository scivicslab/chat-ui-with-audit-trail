package com.scivicslab.chatui.llm;

import com.scivicslab.llmrelay.proxy.RelayInterceptor;
import io.vertx.core.Context;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.httpproxy.ProxyRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * The LLM proxy's rule on llm-relay-proxy (tasks/todo.md, step 4a.2): {@link LlmProxyRequests} decides, and the request
 * is refused (400 or 401, a JSON body) or sent on with the path after {@code /llm/<scope>}, the throw-away key removed
 * and the real credential in its place. The decision asks the keys actor, so it runs off the event loop and answers
 * when ready.
 */
final class ScopeKeyInterceptor implements RelayInterceptor {

    private final LlmProxyRequests requests;
    private final String credentialHeader;

    ScopeKeyInterceptor(LlmProxyRequests requests, String credentialHeader) {
        this.requests = requests;
        this.credentialHeader = credentialHeader;
    }

    @Override
    public CompletionStage<Refusal> intercept(ProxyRequest request) {
        String uri = request.getURI();
        String afterPrefix = uri.startsWith("/llm") ? uri.substring("/llm".length()) : uri;
        Map<String, List<String>> headers = new LinkedHashMap<>();
        request.headers().forEach(e -> headers.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>()).add(e.getValue()));
        // The decision asks the keys actor, so it runs off the event loop; the request is rewritten back in its own
        // Vert.x context.
        Context context = Vertx.currentContext();
        Executor onContext = context == null ? Runnable::run : task -> context.runOnContext(v -> task.run());
        return CompletableFuture.supplyAsync(() -> requests.decide(afterPrefix, headers)).thenApplyAsync(decision -> {
            if (decision instanceof LlmProxyRequests.Refuse refuse) {
                return new Refusal(refuse.status(), "application/json",
                        "{\"error\":\"" + refuse.message().replace("\"", "'") + "\"}");
            }
            LlmProxyRequests.Forward forward = (LlmProxyRequests.Forward) decision;
            String raw = forward.target().getRawPath() + (forward.target().getRawQuery() == null ? "" : "?" + forward.target().getRawQuery());
            request.setURI(raw);
            MultiMap out = request.headers();
            out.remove("x-api-key");
            out.remove("authorization");
            out.set(credentialHeader, forward.headers().get(credentialHeader.toLowerCase(java.util.Locale.ROOT)));
            return null;
        }, onContext);
    }
}
