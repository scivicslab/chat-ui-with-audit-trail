package com.scivicslab.chatui.openaicompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.core.provider.OneShotReply;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One completion from given messages, against a stub server ({@code RequestMessagesControl_261018_oo01}). */
class CompleteFromMessagesTest {

    private HttpServer server;
    private final AtomicReference<String> received = new AtomicReference<>();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"choices\":[{\"message\":{\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private OpenAiCompatProvider provider() {
        return new OpenAiCompatProvider(List.of("http://127.0.0.1:" + server.getAddress().getPort()), "stub-model");
    }

    @Test
    void onlyTheGivenMessagesAreSentAndTheHistoryIsUntouched() {
        OpenAiCompatProvider p = provider();
        p.replaceRequestMessages("[{\"role\":\"user\",\"content\":\"history that must stay\"}]");

        OneShotReply reply = p.completeFromMessages(
                "[{\"role\":\"system\",\"content\":\"judge\"},{\"role\":\"user\",\"content\":\"ping\"}]",
                "stub-model", true);

        assertNull(reply.error());
        assertEquals("pong", reply.reply());
        JSONObject body = new JSONObject(received.get());
        assertEquals(2, body.getJSONArray("messages").length());
        assertEquals("judge", body.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertEquals(received.get(), reply.requestBody());
        assertEquals("[{\"role\":\"user\",\"content\":\"history that must stay\"}]", p.requestMessagesJson());
    }

    @Test
    void aMalformedArrayIsRefusedBeforeAnythingIsSent() {
        OneShotReply reply = provider().completeFromMessages("[{\"role\":\"tool\",\"content\":\"x\"}]", "stub-model", null);

        assertNotNull(reply.error());
        assertNull(reply.requestBody());
        assertNull(received.get());
    }

    @Test
    void anEmptyArrayIsRefused() {
        OneShotReply reply = provider().completeFromMessages("[]", "stub-model", null);

        assertTrue(reply.error().contains("empty"), reply.error());
        assertNull(received.get());
    }
}
