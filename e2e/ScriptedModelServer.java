import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An OpenAI-compatible server that answers from a script, for the browser checks of the console. No real model is
 * involved, so a check gets the same turns every time. Run it outside the Maven build, then start the throwaway
 * instance with {@code -Dchat-ui.servers=http://127.0.0.1:<port> -Dchat-ui.default-model=fake}.
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/ScriptedModelServer.java 28978
 * </pre>
 *
 * The script reads the last message of each call:
 * <ul>
 * <li>{@code SCENARIO-R}: ask chat-03 with {@code ask_chat}; chat-03 answers {@code REPLY-ME} with "R42"; the reply
 * starts a turn that calls {@code accept_reply} and then writes a final answer to the person, {@code FINAL-FOR-PERSON}
 * (ViewToggleCheck).</li>
 * <li>{@code SCENARIO-A}: send two requests to chat-02 (one urgent) and register a promised report on chat-03;
 * {@code TASK-WORK} holds chat-02's turn open (TimekeeperViewCheck).</li>
 * <li>{@code SCENARIO-G}: set superiors and role edges among chat-02..05 (one role target in project2);
 * {@code SCENARIO-G2}: move chat-05 under chat-01, give chat-02's "worker" to chat-05, and make chat-05 chat-04's
 * "tester" and chat-01's "archivist" (a role with no emoji of its own), so chat-05 plays four roles (ConversationGraphCheck).</li>
 * <li>The end-of-turn reminder check registers a reminder only for an answer that says "I will report back".</li>
 * <li>Anything else: "OK. I will report back in 12 minutes."</li>
 * </ul>
 */
public class ScriptedModelServer {

    static final ObjectMapper JSON = new ObjectMapper();

    static final String CALLS_A = """
            <function_calls>
            <invoke name="ask_chat">
            <parameter name="chatId">02</parameter>
            <parameter name="prompt">TASK-WORK please read the long list</parameter>
            <reason>start the long job</reason>
            </invoke>
            <invoke name="ask_chat_urgent">
            <parameter name="chatId">02</parameter>
            <parameter name="prompt">STATUS how far have you got?</parameter>
            <reason>ask for a status report</reason>
            </invoke>
            <invoke name="expect_report">
            <parameter name="chatId">03</parameter>
            <parameter name="requestId">project1/chat-01:s9:t9:r9</parameter>
            <parameter name="minutes">30</parameter>
            <reason>chat-03 promised a report</reason>
            </invoke>
            </function_calls>""";

    static final String GRAPH_G = """
            <function_calls>
            <invoke name="set_superior">
            <parameter name="chatId">03</parameter>
            <parameter name="superiorChatId">02</parameter>
            <reason>build the graph</reason>
            </invoke>
            <invoke name="set_superior">
            <parameter name="chatId">04</parameter>
            <parameter name="superiorChatId">02</parameter>
            <reason>build the graph</reason>
            </invoke>
            <invoke name="set_superior">
            <parameter name="chatId">05</parameter>
            <parameter name="superiorChatId">03</parameter>
            <reason>build the graph</reason>
            </invoke>
            <invoke name="set_collaborator">
            <parameter name="chatId">02</parameter>
            <parameter name="role">worker</parameter>
            <parameter name="collaboratorChatId">03</parameter>
            <reason>build the graph</reason>
            </invoke>
            <invoke name="set_collaborator">
            <parameter name="chatId">02</parameter>
            <parameter name="role">reviewer</parameter>
            <parameter name="collaboratorChatId">04</parameter>
            <reason>build the graph</reason>
            </invoke>
            <invoke name="set_collaborator">
            <parameter name="chatId">03</parameter>
            <parameter name="role">helper</parameter>
            <parameter name="collaboratorChatId">05</parameter>
            <reason>build the graph</reason>
            </invoke>
            <invoke name="set_collaborator">
            <parameter name="chatId">05</parameter>
            <parameter name="role">auditor</parameter>
            <parameter name="collaboratorChatId">project2/05</parameter>
            <reason>build the graph</reason>
            </invoke>
            </function_calls>""";

    static final String GRAPH_G2 = """
            <function_calls>
            <invoke name="set_superior">
            <parameter name="chatId">05</parameter>
            <parameter name="superiorChatId">01</parameter>
            <reason>build the graph</reason>
            </invoke>
            <invoke name="set_collaborator">
            <parameter name="chatId">02</parameter>
            <parameter name="role">worker</parameter>
            <parameter name="collaboratorChatId">05</parameter>
            <reason>build the graph</reason>
            </invoke>
            <invoke name="set_collaborator">
            <parameter name="chatId">04</parameter>
            <parameter name="role">tester</parameter>
            <parameter name="collaboratorChatId">05</parameter>
            <reason>build the graph</reason>
            </invoke>
            <invoke name="set_collaborator">
            <parameter name="chatId">01</parameter>
            <parameter name="role">archivist</parameter>
            <parameter name="collaboratorChatId">05</parameter>
            <reason>a role name with no emoji of its own</reason>
            </invoke>
            </function_calls>""";

    static final String ASK_03 = """
            <function_calls>
            <invoke name="ask_chat">
            <parameter name="chatId">03</parameter>
            <parameter name="prompt">REPLY-ME what is the result?</parameter>
            <reason>chat-03 knows the result</reason>
            </invoke>
            </function_calls>""";

    static final String ACCEPT = """
            <function_calls>
            <invoke name="accept_reply">
            <parameter name="requestId">%s</parameter>
            <reason>the reply answers the question</reason>
            </invoke>
            </function_calls>""";

    static final Pattern REPLY_ID = Pattern.compile("\\[reply to request (\\S+?),");

    static String text(JsonNode message) {
        JsonNode c = message.get("content");
        if (c == null || c.isNull()) return "";
        if (c.isArray()) {
            StringBuilder b = new StringBuilder();
            for (JsonNode part : c) if (part.has("text")) b.append(part.get("text").asText());
            return b.toString();
        }
        return c.asText();
    }

    /** What the script answers to a call whose last message is {@code last}; {@code whole} is every message. */
    static String answer(String last, String whole) throws InterruptedException {
        if (last.contains("bookkeeping check")) {
            return last.contains("I will report back") ? "REGISTER 12 | \"I will report back\"" : "NONE | nothing promised";
        }
        if (last.contains("TASK-WORK")) {
            Thread.sleep(3_600_000);   // the long turn
            return "finished";
        }
        if (last.contains("SCENARIO-A")) return CALLS_A;
        if (last.contains("SCENARIO-G2")) return GRAPH_G2;
        if (last.contains("SCENARIO-G")) return GRAPH_G;
        if (last.contains("SCENARIO-R")) return ASK_03;
        if (last.contains("REPLY-ME")) return "R42 is the result.";
        // The tool preamble quotes "[reply to request ...", so this comes after the markers above.
        if (last.contains("[reply to request ") && !last.contains("Tool result")) {
            Matcher m = REPLY_ID.matcher(last);
            return ACCEPT.formatted(m.find() ? m.group(1) : "unknown");
        }
        if (last.contains("Tool result (accept_reply)")) {
            return "FINAL-FOR-PERSON: chat-03 answered R42. Please publish pojo-actor-distributed 1.1.0, then rebuild and restart.";
        }
        if (last.contains("Tool result (set_")) return "Done: the organization is set.";
        if (last.contains("Tool result (ask_chat)") && whole.contains("SCENARIO-R")) {
            return "Asked chat-03; its reply will start a turn of its own.";
        }
        return "OK. I will report back in 12 minutes.";
    }

    static void send(HttpExchange ex, int code, String contentType, byte[] body) throws java.io.IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }

    static void handle(HttpExchange ex) throws Exception {
        if ("GET".equals(ex.getRequestMethod())) {
            send(ex, 200, "application/json",
                    "{\"object\":\"list\",\"data\":[{\"id\":\"fake\",\"object\":\"model\"}]}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        JsonNode req = JSON.readTree(ex.getRequestBody());
        JsonNode messages = req.path("messages");
        String last = messages.size() > 0 ? text(messages.get(messages.size() - 1)) : "";
        StringBuilder whole = new StringBuilder();
        for (JsonNode m : messages) whole.append(text(m)).append(' ');
        String reply = answer(last, whole.toString());
        if (req.path("stream").asBoolean(false)) {
            StringBuilder sse = new StringBuilder();
            sse.append("data: ").append(chunk(reply, null)).append("\n\n");
            sse.append("data: ").append(chunk(null, "stop")).append("\n\n");
            sse.append("data: [DONE]\n\n");
            send(ex, 200, "text/event-stream", sse.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            ObjectNode body = JSON.createObjectNode();
            body.put("id", "x").put("object", "chat.completion").put("model", "fake");
            ObjectNode choice = body.putArray("choices").addObject();
            choice.put("index", 0).put("finish_reason", "stop");
            choice.putObject("message").put("role", "assistant").put("content", reply);
            body.putObject("usage").put("prompt_tokens", 1).put("completion_tokens", 1).put("total_tokens", 2);
            send(ex, 200, "application/json", JSON.writeValueAsBytes(body));
        }
    }

    static String chunk(String content, String finish) throws Exception {
        ObjectNode d = JSON.createObjectNode();
        ArrayNode choices = d.putArray("choices");
        ObjectNode c = choices.addObject();
        c.put("index", 0);
        ObjectNode delta = c.putObject("delta");
        if (content != null) delta.put("content", content);
        if (finish == null) c.putNull("finish_reason"); else c.put("finish_reason", finish);
        return JSON.writeValueAsString(d);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 28978;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            try {
                handle(ex);
            } catch (Exception e) {
                send(ex, 500, "text/plain", String.valueOf(e).getBytes(StandardCharsets.UTF_8));
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("ScriptedModelServer on 127.0.0.1:" + port);
    }
}
