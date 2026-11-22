package com.scivicslab.chatui.openaicompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Against a stub server that counts tokens the way a real one does for Japanese text (about 1.7 characters
 * to a token, where the provider's estimate assumes 2) and refuses a request whose prompt and
 * {@code max_tokens} do not fit its window, in the words the GLM server uses:
 * {@code maximum context length is W tokens: this request needs a N-token context (P prompt tokens plus
 * max_tokens M)}. The request must fit the first time when the reply budget is capped and the whole request
 * is estimated, and a refusal must shrink the reply budget before any history is trimmed.
 */
class ContextOverflowRetryTest {

    private HttpServer server;
    private int window;
    /** Each request's max_tokens, the number of its messages, and its body's length, in order. */
    private final List<int[]> requests = new CopyOnWriteArrayList<>();

    private static int counted(String body) {
        return (int) Math.ceil(body.length() / 1.7);
    }

    private void startStub(int window) throws Exception {
        this.window = window;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> {
            byte[] out = ("{\"data\":[{\"id\":\"stub-model\",\"max_model_len\":" + window + "}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JSONObject request = new JSONObject(body);
            int max = request.optInt("max_tokens", 0);
            int messages = request.getJSONArray("messages").length();
            requests.add(new int[] {max, messages, body.length()});
            int prompt = counted(body);
            if (prompt + max > window) {
                String error = "{\"error\": {\"message\": \"This server's maximum context length is " + window
                        + " tokens: this request needs a " + (prompt + max) + "-token context (" + prompt
                        + " prompt tokens plus max_tokens " + max + "), which exceeds the context window this server"
                        + " was started for\", \"type\": \"invalid_request_error\", \"param\": \"messages\","
                        + " \"code\": \"context_length_exceeded\"}}";
                byte[] out = error.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(400, out.length);
                ex.getResponseBody().write(out);
                ex.close();
                return;
            }
            String sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"done\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
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

    /** A history of {@code count} messages, user and assistant in turn, of Japanese text of about {@code chars} each. */
    private static String history(int count, int chars) {
        String sentence = "会話の履歴に残っている日本語の文章です。";
        StringBuilder text = new StringBuilder();
        while (text.length() < chars) text.append(sentence);
        JSONArray messages = new JSONArray();
        for (int i = 0; i < count; i++) {
            messages.put(new JSONObject().put("role", i % 2 == 0 ? "user" : "assistant")
                    .put("content", i + ": " + text.substring(0, chars)));
        }
        return messages.toString();
    }

    private OpenAiCompatProvider provider(String historyJson) {
        OpenAiCompatProvider p = new OpenAiCompatProvider(
                List.of("http://127.0.0.1:" + server.getAddress().getPort()), "stub-model");
        p.getAvailableModels();
        if (historyJson != null) {
            String refused = p.replaceRequestMessages(historyJson);
            assertEquals(null, refused, "the history is within the budget");
        }
        return p;
    }

    private static List<ChatEvent> send(OpenAiCompatProvider p) {
        List<ChatEvent> events = new ArrayList<>();
        p.sendPrompt("次の段取りを書いてください。", "stub-model", events::add,
                new ProviderContext(null, List.of(), false, () -> {}, null, false));
        return events;
    }

    private static boolean answered(List<ChatEvent> events) {
        return events.stream().anyMatch(e -> "result".equals(e.type()))
                && events.stream().noneMatch(e -> "error".equals(e.type()));
    }

    /**
     * The turn that was refused on project9/chat-01: a 262,144-token window and 86 messages of 62,672
     * characters of Japanese, with the tool definitions. It must be accepted on the first request, its reply
     * budget and its real prompt inside the window.
     */
    @Test
    void theRefusedTurn_isAcceptedOnTheFirstRequest() throws Exception {
        startStub(262_144);
        List<ChatEvent> events = send(provider(history(86, 729)));

        assertTrue(answered(events), events.toString());
        assertEquals(1, requests.size(), "requests: " + requests.size());
        int[] first = requests.get(0);
        assertTrue(first[0] + (int) Math.ceil(first[2] / 1.7) <= window,
                "max_tokens " + first[0] + " + prompt " + (int) Math.ceil(first[2] / 1.7) + " > " + window);
    }

    /**
     * A refusal whose message gives the prompt's real count shrinks the reply budget and sends the same
     * messages again; nothing is trimmed while the prompt alone leaves room for a reply.
     */
    @Test
    void aRefusal_shrinksTheReplyBudget_andKeepsTheHistory() throws Exception {
        startStub(100_000);
        List<ChatEvent> events = send(provider(history(60, 1_170)));

        assertTrue(answered(events), events.toString());
        assertTrue(requests.size() >= 2, "the first request is refused: " + requests.size());
        int[] first = requests.get(0);
        int[] last = requests.get(requests.size() - 1);
        assertTrue(last[0] < first[0], "max_tokens shrank: " + first[0] + " -> " + last[0]);
        assertEquals(first[1], last[1], "the same messages were sent again; nothing was trimmed");
        assertTrue(events.stream().noneMatch(e -> e.content() != null && e.content().contains("trimming history")),
                events.toString());
    }

    /**
     * The estimate covers the whole request: the tool definitions and the JSON around the messages count, not
     * only the history's text. With a window small enough that the cap does not bind, the reply budget leaves
     * room for the request body as the estimate counts it.
     */
    @Test
    void theEstimate_countsTheWholeRequest_toolDefinitionsIncluded() throws Exception {
        startStub(40_000);
        send(provider(null));

        int[] first = requests.get(0);
        int margin = Math.max(1_024, (int) (window * 0.02));
        int estimatedBody = (int) Math.ceil(first[2] / 2.0);
        // The body received carries "max_tokens":N, which the estimate before sending did not: allow its length.
        assertTrue(first[0] + estimatedBody <= window - margin + 16,
                "max_tokens " + first[0] + " + the body's estimate " + estimatedBody + " > window " + window
                        + " - margin " + margin);
    }
}
