package com.scivicslab.chatui.openaicompat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A server can fail after it has already answered 200 and started the stream. vLLM-style servers
 * and the GPU broker then send the error as one more {@code data:} chunk, {@code {"error":{...}}},
 * followed by {@code [DONE]}. Read as an ordinary chunk it carries no content, so the call looked
 * like a model that answered with nothing, and the turn ended "answered" with an empty reply.
 */
class ErrorInsideStreamTest {

    private static final String ERROR_CHUNK =
            "data: {\"error\": {\"message\": \"the two ranks are out of step after an error; restart both\","
            + " \"type\": \"server_error\"}}";

    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = "data: {\"id\": \"x\", \"object\": \"chat.completion.chunk\", \"choices\":"
                    + " [{\"index\": 0, \"delta\": {\"role\": \"assistant\"}, \"finish_reason\": null}]}\n\n"
                    + ERROR_CHUNK + "\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void parseSseDelta_errorChunk_carriesTheServersMessage() {
        OpenAiCompatClient.SseDelta delta = OpenAiCompatClient.parseSseDelta(ERROR_CHUNK);
        assertNotNull(delta);
        assertEquals("the two ranks are out of step after an error; restart both", delta.error());
        assertNull(delta.content());
    }

    @Test
    void parseSseDelta_contentThatMentionsError_isNotAnError() {
        OpenAiCompatClient.SseDelta delta = OpenAiCompatClient.parseSseDelta(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"{\\\"error\\\": {\\\"message\\\": \\\"x\\\"}}\"},"
                + "\"finish_reason\":null}]}");
        assertNull(delta.error());
    }

    @Test
    void sendPrompt_errorAfterTheStreamStarted_isReportedAsAnError() {
        OpenAiCompatClient client = new OpenAiCompatClient(
                "http://127.0.0.1:" + server.getAddress().getPort());
        AtomicReference<String> error = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        String reply = client.sendPrompt("m", List.of(new ChatMessage.User("hi")), false, 0,
                new OpenAiCompatClient.StreamCallback() {
                    @Override public void onDelta(String content) {}
                    @Override public void onComplete(long durationMs, String finishReason) {
                        completed.set(true);
                    }
                    @Override public void onError(String message) { error.set(message); }
                });
        assertNull(reply);
        assertNotNull(error.get(), "the server's error must reach the caller");
        assertTrue(error.get().contains("the two ranks are out of step"), error.get());
        assertTrue(!completed.get(), "a failed call must not also report completion");
    }
}
