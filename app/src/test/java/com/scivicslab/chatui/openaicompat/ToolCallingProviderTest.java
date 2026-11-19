package com.scivicslab.chatui.openaicompat;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The openai-compat provider against a server that answers with streamed {@code tool_calls}
 * (OpenAiCompatStandardToolCalling_261120_oo01, achieved states 1, 3, 4, 5 and 6).
 */
@Tag("OpenAiCompatStandardToolCalling_261120_oo01")
@DisplayName("OpenAiCompatProvider sends tools, reports tool_calls, returns results and keeps them across turns")
class ToolCallingProviderTest {

    HttpServer server;
    final List<JSONObject> bodies = new ArrayList<>();
    final Deque<String> replies = new ConcurrentLinkedDeque<>();

    static String toolCallReply(String id, String name, String arguments) {
        JSONObject call = new JSONObject().put("index", 0).put("id", id).put("type", "function")
                .put("function", new JSONObject().put("name", name).put("arguments", arguments));
        return "data: " + new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("delta", new JSONObject().put("tool_calls", new JSONArray().put(call))))) + "\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
    }

    static String textReply(String text) {
        return "data: " + new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("delta", new JSONObject().put("content", text)))) + "\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> send(ex, "{\"data\":[{\"id\":\"m\",\"max_model_len\":32768}]}", "application/json"));
        server.createContext("/v1/chat/completions", ex -> {
            synchronized (bodies) {
                bodies.add(new JSONObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            }
            send(ex, replies.pollFirst(), "text/event-stream");
        });
        server.start();
    }

    static void send(com.sun.net.httpserver.HttpExchange ex, String body, String type) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    OpenAiCompatProvider provider() {
        OpenAiCompatProvider p = new OpenAiCompatProvider(
                List.of("http://127.0.0.1:" + server.getAddress().getPort()), "m");
        p.getAvailableModels();
        return p;
    }

    static List<ChatEvent> send(OpenAiCompatProvider p, String prompt) {
        List<ChatEvent> events = new ArrayList<>();
        p.sendPrompt(prompt, null, events::add, new ProviderContext(null, List.of(), true, () -> {}, null, true));
        return events;
    }

    static JSONArray history(OpenAiCompatProvider p) {
        return new JSONArray(p.requestMessagesJson());
    }

    @Test
    void aWholeTurnWithOneToolCall() {
        OpenAiCompatProvider p = provider();
        p.setSystemMessage("SYSTEM NOTES");
        replies.add(toolCallReply("call_1", "read", "{\"path\": \"notes.txt\"}"));
        replies.add(textReply("It says hello."));

        List<ChatEvent> first = send(p, "What does notes.txt say?");
        List<ChatEvent> calls = first.stream().filter(e -> "tool_call".equals(e.type())).toList();
        assertEquals(1, calls.size());
        assertEquals("call_1", calls.get(0).toolUseId());
        assertEquals("read", calls.get(0).toolName());
        assertEquals("{\"path\": \"notes.txt\"}", calls.get(0).content());

        JSONObject firstBody = bodies.get(0);
        assertEquals("system", firstBody.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertEquals("SYSTEM NOTES", firstBody.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertEquals("What does notes.txt say?", firstBody.getJSONArray("messages").getJSONObject(1).getString("content"));
        List<String> toolNames = new ArrayList<>();
        firstBody.getJSONArray("tools").forEach(t -> toolNames.add(((JSONObject) t).getJSONObject("function").getString("name")));
        assertTrue(toolNames.contains("ask_person") && toolNames.contains("ask_chat"), toolNames.toString());
        assertFalse(firstBody.toString().contains("<invoke"), "no call format is taught");

        p.addToolResults(List.of(new LlmProvider.ToolCallResult("call_1", "read", "hello")));
        List<ChatEvent> second = send(p, "");
        assertTrue(second.stream().noneMatch(e -> "tool_call".equals(e.type())));
        JSONArray sent = bodies.get(1).getJSONArray("messages");
        JSONObject last = sent.getJSONObject(sent.length() - 1);
        assertEquals("tool", last.getString("role"), "a blank prompt adds no user message after the results");
        assertEquals("call_1", last.getString("tool_call_id"));
        JSONObject asked = sent.getJSONObject(sent.length() - 2);
        assertEquals("assistant", asked.getString("role"));
        assertEquals("call_1", asked.getJSONArray("tool_calls").getJSONObject(0).getString("id"));

        p.collapseTurn("What does notes.txt say?", "It says hello.");
        JSONArray kept = history(p);
        assertEquals(List.of("system", "user", "assistant", "tool", "assistant"), roles(kept));
        assertEquals("It says hello.", kept.getJSONObject(4).getString("content"));
    }

    @Test
    void aCallWithoutAResultAndTheNotesBetweenStepsAreNotKept() {
        OpenAiCompatProvider p = provider();
        p.setSystemMessage("S");
        replies.add(textReply("Partial."));
        replies.add(toolCallReply("call_9", "ask_person", "{\"question\":\"Which file?\"}"));
        send(p, "Read the file.");
        send(p, "Go on.");
        p.collapseTurn("Read the file.", "Which file?");
        JSONArray kept = history(p);
        assertEquals(List.of("system", "user", "assistant"), roles(kept));
        assertEquals("Which file?", kept.getJSONObject(2).getString("content"));
    }

    @Test
    void theSystemMessageIsReplacedNotAdded() {
        OpenAiCompatProvider p = provider();
        p.setSystemMessage("one");
        p.setSystemMessage("two");
        JSONArray h = history(p);
        assertEquals(1, h.length());
        assertEquals("two", h.getJSONObject(0).getString("content"));
    }

    @Test
    void withoutAnMcpServerAToolCannotBeRun() {
        assertTrue(provider().callTool("read", "{}").startsWith("error: "));
    }

    static List<String> roles(JSONArray messages) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < messages.length(); i++) r.add(messages.getJSONObject(i).getString("role"));
        return r;
    }
}
