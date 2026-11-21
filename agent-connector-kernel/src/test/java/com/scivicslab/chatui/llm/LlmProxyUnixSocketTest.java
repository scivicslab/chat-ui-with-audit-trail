package com.scivicslab.chatui.llm;

import com.scivicslab.chatui.unixhttp.UnixSocketClient;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LLM proxy on a Unix domain socket, in front of a throw-away upstream on loopback that records the credential it
 * is sent (tasks/todo.md, Decisions 10; stage 9 part 1). Dummy keys only.
 */
class LlmProxyUnixSocketTest {

    @Test
    void forwardsOverTheSocketWithTheRealCredentialAndRefusesAWrongKey(@TempDir Path root) throws Exception {
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
        ActorSystem system = new ActorSystem("llm-proxy-unix-test");
        Path socket = root.resolve("run").resolve("llm.sock");
        try (LlmProxy proxy = new LlmProxy(system, socket, URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                "x-api-key", "dummy-real-credential")) {
            assertNull(proxy.address(), "no TCP port on the socket form");
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)));
            String key = proxy.register("s1");
            UnixSocketClient.Answer ok = UnixSocketClient.send(socket, "POST", "/s1/v1/messages",
                    Map.of("x-api-key", key, "Content-Type", "application/json"), "{}");
            assertEquals(200, ok.status());
            assertTrue(ok.body().contains("message_stop"), ok.body());
            assertEquals("dummy-real-credential", seen.get());

            seen.set(null);
            UnixSocketClient.Answer refused = UnixSocketClient.send(socket, "POST", "/s1/v1/messages",
                    Map.of("x-api-key", "wrong"), "{}");
            assertEquals(401, refused.status());
            assertNull(seen.get(), "a refused request never reaches the upstream");
        } finally {
            upstream.stop(0);
            system.terminate();
        }
    }
}
