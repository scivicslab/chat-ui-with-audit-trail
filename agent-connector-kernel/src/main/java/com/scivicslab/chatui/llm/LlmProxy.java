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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * agent-connector-kernel's LLM proxy, in front of one LLM provider ({@code LlmKeyInjectionProxy_261121_oo01};
 * tasks/todo.md stage 8). A harness gets a throw-away key; the proxy checks it, puts the real credential in its
 * place, and passes the answer back as it arrives, Server-Sent Events included.
 *
 * <p>It listens on the loopback address over TCP. Its Unix-socket form is gone with the hand-written Unix-socket server
 * (tasks/todo.md, step 3): the proxy moves onto the Quarkus HTTP server, and so onto its domain socket at
 * {@code /llm/<scope>/v1}, in step 4. The decisions are {@link LlmProxyRequests}'. The throw-away keys live in a
 * {@link ProxyKeys} actor, and
 * requests run on the {@link ActorSystem}'s managed thread pool, so one long streaming answer does not hold up the
 * others. Built on the JDK alone ({@code com.sun.net.httpserver}, {@code java.net.http}, {@code java.nio}), so it
 * adds no dependency and runs from any product's process.</p>
 */
public final class LlmProxy implements AutoCloseable {

    /** Response headers not passed back: the ones the server writes itself. */
    private static final Set<String> NOT_PASSED_BACK = Set.of("content-length", "transfer-encoding", "connection",
            "keep-alive", ":status");

    /** Where one answer goes: a refusal with a JSON body, or a status and headers followed by a streamed body. */
    private interface Reply {
        void refuse(int status, byte[] json) throws IOException;

        OutputStream stream(int status, Map<String, List<String>> headers) throws IOException;

        void fail(int status) throws IOException;
    }

    private final HttpServer http;
    private final ActorRef<ProxyKeys> keys;
    private final LlmProxyRequests requests;
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
        this.requests = requests(keys, upstream, credentialHeader, credential);
        this.http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.createContext("/", this::answer);
        http.setExecutor(system.getManagedThreadPool());
        http.start();
    }

    private static LlmProxyRequests requests(ActorRef<ProxyKeys> keys, URI upstream, String credentialHeader,
                                             String credential) {
        return new LlmProxyRequests(
                scope -> keys.ask(k -> k.has(scope)).join(),
                (scope, key) -> keys.ask(k -> k.opens(scope, key)).join(),
                upstream, credentialHeader, credential);
    }

    /** @return the loopback address the proxy is bound to */
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

    /** @return the base URL the scope's harness is given on loopback TCP, e.g. {@code http://127.0.0.1:41235/s1} */
    public String baseUrl(String scope) {
        return "http://" + http.getAddress().getAddress().getHostAddress() + ":" + http.getAddress().getPort() + "/" + scope;
    }

    private void answer(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            String target = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            forward(exchange.getRequestMethod(), target, exchange.getRequestHeaders(), body, new Reply() {
                @Override public void refuse(int status, byte[] json) throws IOException {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, json.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(json);
                    }
                }

                @Override public OutputStream stream(int status, Map<String, List<String>> headers) throws IOException {
                    headers.forEach((name, values) -> exchange.getResponseHeaders().put(name, values));
                    exchange.sendResponseHeaders(status, 0);
                    return exchange.getResponseBody();
                }

                @Override public void fail(int status) throws IOException {
                    exchange.sendResponseHeaders(status, -1);
                }
            });
        }
    }

    /** Refuses, or forwards to the upstream and streams the answer back. */
    private void forward(String method, String target, Map<String, List<String>> headers, byte[] body, Reply reply)
            throws IOException {
        LlmProxyRequests.Decision decision = requests.decide(target, headers);
        if (decision instanceof LlmProxyRequests.Refuse refuse) {
            reply.refuse(refuse.status(),
                    ("{\"error\":\"" + refuse.message().replace("\"", "'") + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        LlmProxyRequests.Forward forward = (LlmProxyRequests.Forward) decision;
        HttpRequest.Builder out = HttpRequest.newBuilder(forward.target())
                .method(method, body.length == 0
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        forward.headers().forEach(out::header);
        HttpResponse<InputStream> response;
        try {
            response = client.send(out.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply.fail(502);
            return;
        } catch (IOException e) {
            reply.fail(502);
            return;
        }
        Map<String, List<String>> passed = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!NOT_PASSED_BACK.contains(name.toLowerCase())) passed.put(name, values);
        });
        try (InputStream in = response.body(); OutputStream o = reply.stream(response.statusCode(), passed)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                o.write(buffer, 0, n);
                o.flush();
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
