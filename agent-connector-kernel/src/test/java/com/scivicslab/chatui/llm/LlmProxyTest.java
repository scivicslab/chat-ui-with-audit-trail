package com.scivicslab.chatui.llm;

import com.scivicslab.pojoactor.core.ActorSystem;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * agent-connector-kernel's LLM proxy on llm-relay-proxy (tasks/todo.md, step 4a.2): at {@code /llm/<scope>/v1} on a
 * Vert.x router, as the Quarkus router carries it. Dummy keys and a stub upstream only.
 */
class LlmProxyTest {

    private static final String REAL = "Bearer dummy-real-credential";

    private Vertx vertx;
    private ActorSystem system;
    private HttpServer upstream;
    private HttpServer front;
    private LlmProxy proxy;
    private final Map<String, String> seen = new ConcurrentHashMap<>();
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
        system = new ActorSystem("llm-proxy-test");
    }

    @AfterEach
    void stop() throws Exception {
        if (proxy != null) proxy.close();
        for (HttpServer s : new HttpServer[] {upstream, front}) {
            if (s != null) s.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        system.terminate();
    }

    private int upstream(Consumer<HttpServerRequest> answer) throws Exception {
        upstream = vertx.createHttpServer().requestHandler(answer::accept).listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        return upstream.actualPort();
    }

    /** @return the front's base URL; the proxy is mounted on a router as on Quarkus's */
    private String front(URI upstreamUri) throws Exception {
        Router router = Router.router(vertx);
        proxy = new LlmProxy(vertx, system, upstreamUri, "authorization", REAL);
        proxy.mount(router);
        front = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        return "http://127.0.0.1:" + front.actualPort();
    }

    private HttpResponse<InputStream> post(String url, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static String text(HttpResponse<InputStream> r) throws Exception {
        return new String(r.body().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void noKeyAWrongKeyAndAnUnknownScopeGetTheSame401AndNoScopeGets400() throws Exception {
        int port = upstream(req -> {
            seen.put("called", "yes");
            req.response().end("should not be reached");
        });
        String base = front(URI.create("http://127.0.0.1:" + port));
        String key = proxy.register("s1");
        HttpResponse<InputStream> none = post(base + "/llm/s1/v1/chat/completions", Map.of(), "{}");
        HttpResponse<InputStream> wrong = post(base + "/llm/s1/v1/chat/completions",
                Map.of("Authorization", "Bearer " + key + "x"), "{}");
        HttpResponse<InputStream> unknown = post(base + "/llm/s2/v1/chat/completions",
                Map.of("Authorization", "Bearer " + key), "{}");
        String noneBody = text(none);
        assertEquals(401, none.statusCode());
        assertEquals(401, wrong.statusCode());
        assertEquals(401, unknown.statusCode());
        assertEquals(noneBody, text(wrong));
        assertEquals(noneBody, text(unknown));
        assertEquals(400, post(base + "/llm/", Map.of("Authorization", "Bearer " + key), "{}").statusCode());
        assertNull(seen.get("called"), "no refused request reached the upstream");
    }

    /** Both shapes: the throw-away key goes, the real credential comes, and the path after the scope is kept. */
    @Test
    void theRightKeyIsForwardedWithTheRealCredentialInBothShapes() throws Exception {
        int port = upstream(req -> req.body().onSuccess(b -> {
            seen.put(req.path() + " auth", String.valueOf(req.getHeader("Authorization")));
            seen.put(req.path() + " x-api-key", String.valueOf(req.getHeader("x-api-key")));
            seen.put(req.path() + " version", String.valueOf(req.getHeader("anthropic-version")));
            seen.put(req.path() + " uri", req.uri());
            seen.put(req.path() + " body", b.toString());
            req.response().putHeader("Content-Type", "application/json").end("{\"ok\":true}");
        }));
        String base = front(URI.create("http://127.0.0.1:" + port));
        String key = proxy.register("s1");
        HttpResponse<InputStream> openai = post(base + "/llm/s1/v1/chat/completions?x=1",
                Map.of("Authorization", "Bearer " + key), "{\"model\":\"m\"}");
        HttpResponse<InputStream> anthropic = post(base + "/llm/s1/v1/messages",
                Map.of("x-api-key", key, "anthropic-version", "2023-06-01"), "{\"model\":\"m\"}");
        assertEquals(200, openai.statusCode());
        assertEquals("{\"ok\":true}", text(openai));
        assertEquals(200, anthropic.statusCode());
        assertEquals("/v1/chat/completions?x=1", seen.get("/v1/chat/completions uri"));
        assertEquals(REAL, seen.get("/v1/chat/completions auth"));
        assertEquals("{\"model\":\"m\"}", seen.get("/v1/chat/completions body"));
        assertEquals(REAL, seen.get("/v1/messages auth"));
        assertEquals("null", seen.get("/v1/messages x-api-key"), "the throw-away key is not passed on");
        assertEquals("2023-06-01", seen.get("/v1/messages version"));
        assertTrue(seen.values().stream().noneMatch(v -> v.contains(key)), "the throw-away key reaches no upstream header");
    }

    /** Several SSE chunks, in order; the first reaches the client while the upstream still waits. */
    @Test
    void aStreamPassesThroughUnbrokenAndUnbuffered() throws Exception {
        CompletableFuture<Void> release = new CompletableFuture<>();
        List<String> lines = List.of("data: {\"n\":1}", "data: {\"n\":2}", "data: {\"n\":3}", "data: [DONE]");
        int port = upstream(req -> {
            req.response().setChunked(true).putHeader("Content-Type", "text/event-stream");
            req.response().write(lines.get(0) + "\n\n");
            release.thenRun(() -> vertx.runOnContext(v -> {
                for (String line : lines.subList(1, lines.size())) req.response().write(line + "\n\n");
                req.response().end();
            }));
        });
        String base = front(URI.create("http://127.0.0.1:" + port));
        String key = proxy.register("s1");
        HttpResponse<InputStream> r = post(base + "/llm/s1/v1/chat/completions", Map.of("Authorization", "Bearer " + key),
                "{\"stream\":true}");
        assertEquals(200, r.statusCode());
        BufferedReader reader = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8));
        assertEquals(lines.get(0), reader.readLine());
        assertFalse(release.isDone(), "the first chunk arrived before the upstream went on");
        release.complete(null);
        StringBuilder rest = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) if (!line.isEmpty()) rest.append(line).append('\n');
        assertEquals(String.join("\n", lines.subList(1, lines.size())) + "\n", rest.toString());
    }

    /** An upstream that cannot be reached is answered 502; an upstream's own 5xx reaches the client as it is. */
    @Test
    void anUpstreamErrorBeforeTheFirstByteIsAnswered() throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        String base = front(URI.create("http://127.0.0.1:" + free));
        String key = proxy.register("s1");
        assertEquals(502, post(base + "/llm/s1/v1/chat/completions", Map.of("Authorization", "Bearer " + key), "{}")
                .statusCode());
        proxy.close();
        front.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        int port = upstream(req -> req.response().setStatusCode(503).end("{\"error\":\"busy\"}"));
        base = front(URI.create("http://127.0.0.1:" + port));
        key = proxy.register("s1");
        HttpResponse<InputStream> busy = post(base + "/llm/s1/v1/chat/completions", Map.of("Authorization", "Bearer " + key), "{}");
        assertEquals(503, busy.statusCode());
        assertEquals("{\"error\":\"busy\"}", text(busy));
    }

    /** TLS to the upstream is not built yet; an https upstream is refused rather than sent in clear. */
    @Test
    void anHttpsUpstreamIsRefusedForNow() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmProxy(vertx, system, URI.create("https://api.example.invalid"), "x-api-key", "k"));
    }
}
