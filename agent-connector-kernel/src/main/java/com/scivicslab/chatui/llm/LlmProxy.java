package com.scivicslab.chatui.llm;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * agent-connector-kernel's LLM proxy: HTTP on the loopback address only, in front of one LLM provider
 * ({@code LlmKeyInjectionProxy_261121_oo01}; tasks/todo.md stage 8). A harness gets a throw-away key; the proxy checks
 * it, puts the real credential in its place, and passes the answer back as it arrives, Server-Sent Events included.
 *
 * <p>The throw-away keys live in a {@link ProxyKeys} actor. Requests run on the {@link ActorSystem}'s managed thread
 * pool, so one long streaming answer does not hold up the others. What a request gets is decided by
 * {@link LlmProxyRequests}. Built on the JDK's {@code com.sun.net.httpserver} and {@code java.net.http}, so it adds no
 * dependency and runs from any product's process.</p>
 */
public final class LlmProxy implements AutoCloseable {

    /** Response headers not passed back: the ones the HTTP server sets itself. */
    private static final Set<String> NOT_PASSED_BACK = Set.of("content-length", "transfer-encoding", "connection",
            "keep-alive", ":status");

    private final HttpServer http;
    private final ActorRef<ProxyKeys> keys;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    /**
     * Starts the proxy on the loopback address.
     *
     * @param system           the actor system the keys actor and the request threads belong to
     * @param port             the port, or 0 for any free one
     * @param upstream         the provider's base URL, e.g. {@code https://api.anthropic.com}
     * @param credentialHeader the header the provider reads the credential from, e.g. {@code x-api-key}
     * @param credential       the real credential
     */
    public LlmProxy(ActorSystem system, int port, URI upstream, String credentialHeader, String credential)
            throws IOException {
        this.keys = system.actorOf("llm-proxy-keys", new ProxyKeys());
        LlmProxyRequests requests = new LlmProxyRequests(
                scope -> keys.ask(k -> k.has(scope)).join(),
                (scope, key) -> keys.ask(k -> k.opens(scope, key)).join(),
                upstream, credentialHeader, credential);
        this.http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.createContext("/", exchange -> answer(exchange, requests));
        http.setExecutor(system.getManagedThreadPool());
        http.start();
    }

    /** @return the address the proxy is bound to */
    public InetSocketAddress address() {
        return http.getAddress();
    }

    /**
     * Opens a scope to the proxy.
     *
     * @return the throw-away key the scope's harness is given
     */
    public String register(String scope) {
        return keys.ask(k -> k.register(scope)).join();
    }

    /** Closes a scope; its throw-away key no longer opens anything. */
    public void unregister(String scope) {
        keys.tell(k -> k.unregister(scope)).join();
    }

    /** @return the base URL the scope's harness is given, e.g. {@code http://127.0.0.1:41235/s1} */
    public String baseUrl(String scope) {
        return "http://" + http.getAddress().getAddress().getHostAddress() + ":" + http.getAddress().getPort() + "/" + scope;
    }

    private void answer(HttpExchange exchange, LlmProxyRequests requests) throws IOException {
        try (exchange) {
            LlmProxyRequests.Decision decision = requests.decide(exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()),
                    exchange.getRequestHeaders());
            if (decision instanceof LlmProxyRequests.Refuse refuse) {
                byte[] body = ("{\"error\":\"" + refuse.message().replace("\"", "'") + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(refuse.status(), body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            LlmProxyRequests.Forward forward = (LlmProxyRequests.Forward) decision;
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            HttpRequest.Builder out = HttpRequest.newBuilder(forward.target())
                    .method(exchange.getRequestMethod(), body.length == 0
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            forward.headers().forEach(out::header);
            HttpResponse<InputStream> response;
            try {
                response = client.send(out.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.sendResponseHeaders(502, -1);
                return;
            } catch (IOException e) {
                exchange.sendResponseHeaders(502, -1);
                return;
            }
            for (Map.Entry<String, List<String>> h : response.headers().map().entrySet()) {
                if (!NOT_PASSED_BACK.contains(h.getKey().toLowerCase())) exchange.getResponseHeaders().put(h.getKey(), h.getValue());
            }
            exchange.sendResponseHeaders(response.statusCode(), 0);
            try (InputStream in = response.body(); OutputStream o = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    o.write(buffer, 0, n);
                    o.flush();
                }
            }
        }
    }

    /** Stops the proxy at once and closes the keys actor. */
    @Override
    public void close() {
        http.stop(0);
        keys.close();
    }
}
