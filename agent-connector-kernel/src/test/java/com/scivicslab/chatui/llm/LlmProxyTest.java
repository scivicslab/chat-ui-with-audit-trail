package com.scivicslab.chatui.llm;

import com.scivicslab.pojoactor.core.ActorSystem;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LLM proxy over real HTTP on the loopback address, in front of a throw-away upstream that records the credential
 * it is sent (tasks/todo.md stage 8). Every key here is a dummy.
 */
class LlmProxyTest {

    @Test
    void forwardsWithTheRealCredentialAndRefusesAWrongKey() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] body = "data: {\"type\":\"message_stop\"}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        ActorSystem system = new ActorSystem("llm-proxy-test");
        URI upstreamUri = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort());
        try (LlmProxy proxy = new LlmProxy(system, 0, upstreamUri, "x-api-key", "dummy-real-credential")) {
            assertTrue(proxy.address().getAddress().isLoopbackAddress());
            String key = proxy.register("s1");
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> ok = client.send(HttpRequest.newBuilder(URI.create(proxy.baseUrl("s1") + "/v1/messages"))
                    .header("x-api-key", key).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("message_stop"), ok.body());
            assertEquals("dummy-real-credential", seen.get());

            seen.set(null);
            HttpResponse<String> refused = client.send(HttpRequest.newBuilder(URI.create(proxy.baseUrl("s1") + "/v1/messages"))
                    .header("x-api-key", "wrong").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, refused.statusCode());
            assertNull(seen.get(), "a refused request never reaches the upstream");
        } finally {
            upstream.stop(0);
            system.terminate();
        }
    }
}
