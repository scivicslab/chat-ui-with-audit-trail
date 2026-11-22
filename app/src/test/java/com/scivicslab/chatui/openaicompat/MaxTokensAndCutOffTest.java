package com.scivicslab.chatui.openaicompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Against a stub server: the request carries {@code max_tokens} whether the model's window is known or not,
 * capped at the most a reply may run to, and a reply the server cut off at the token limit is kept in the history, not
 * thrown away, when the caller handles it ({@code CutOffReplyContinues_261115_oo01}).
 */
class MaxTokensAndCutOffTest {

    private HttpServer server;
    private final AtomicReference<String> received = new AtomicReference<>();

    private void startStub(String modelsJson, String finishReason) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> {
            byte[] out = modelsJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/v1/chat/completions", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial text\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + finishReason + "\"}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] out = sse.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        if (server != null) server.stop(0);
    }

    private OpenAiCompatProvider provider() {
        OpenAiCompatProvider p = new OpenAiCompatProvider(
                List.of("http://127.0.0.1:" + server.getAddress().getPort()), "stub-model");
        p.getAvailableModels();
        return p;
    }

    private List<ChatEvent> send(OpenAiCompatProvider p, boolean handlesCutOff) {
        List<ChatEvent> events = new ArrayList<>();
        p.sendPrompt("write something", "stub-model", events::add,
                new ProviderContext(null, List.of(), false, () -> {}, null, handlesCutOff));
        return events;
    }

    @Test
    void windowKnown_requestCarriesTheRoomLeft() throws Exception {
        startStub("{\"data\":[{\"id\":\"stub-model\",\"max_model_len\":100000}]}", "stop");
        send(provider(), false);

        JSONObject body = new JSONObject(received.get());
        assertTrue(body.has("max_tokens"), received.get());
        int sent = body.getInt("max_tokens");
        assertEquals(OpenAiCompatProvider.DEFAULT_MAX_REPLY_TOKENS, sent,
                "the room left in a 100000-token window, capped at the most a reply may run to: " + sent);
    }

    @Test
    void windowUnknown_requestStillCarriesMaxTokens() throws Exception {
        startStub("{\"data\":[{\"id\":\"stub-model\"}]}", "stop");
        send(provider(), false);

        // Sent every time: without it the server applies its own default, 32,768 on one deployment.
        JSONObject body = new JSONObject(received.get());
        assertTrue(body.has("max_tokens"), received.get());
        assertTrue(body.getInt("max_tokens") <= OpenAiCompatProvider.DEFAULT_MAX_REPLY_TOKENS, received.get());
    }

    @Test
    void cutOffAndHandled_keepsThePartialReplyAndSaysSoWithoutAnError() throws Exception {
        startStub("{\"data\":[{\"id\":\"stub-model\",\"max_model_len\":100000}]}", "length");
        OpenAiCompatProvider p = provider();
        List<ChatEvent> events = send(p, true);

        assertTrue(events.stream().noneMatch(e -> "error".equals(e.type())), events.toString());
        ChatEvent cut = events.stream().filter(e -> "cut_off".equals(e.type())).findFirst().orElseThrow();
        assertEquals(String.valueOf("partial text".length()), cut.content());
        assertTrue(events.stream().noneMatch(e -> "result".equals(e.type())), "a cut-off is not a completed reply");
        JSONObject last = new org.json.JSONArray(p.requestMessagesJson()).getJSONObject(1);
        assertEquals("assistant", last.getString("role"));
        assertEquals("partial text", last.getString("content"));
    }

    @Test
    void cutOffAndNotHandled_failsAndKeepsNothing_asBefore() throws Exception {
        startStub("{\"data\":[{\"id\":\"stub-model\",\"max_model_len\":100000}]}", "length");
        OpenAiCompatProvider p = provider();
        List<ChatEvent> events = send(p, false);

        assertTrue(events.stream().anyMatch(e -> "error".equals(e.type())
                && e.content().contains("cut off at the token limit")), events.toString());
        assertEquals(1, new org.json.JSONArray(p.requestMessagesJson()).length(), "only the prompt is in the history");
    }
}
