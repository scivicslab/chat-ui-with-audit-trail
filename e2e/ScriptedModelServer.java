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
 * "tester" and chat-01's "archivist" (a role with no emoji of its own), so chat-05 plays four roles;
 * {@code SCENARIO-G3}: make project2/chat-01 chat-02's "translator", a role edge into another project;
 * {@code SCENARIO-G4}: make chat-01 on node b chat-03's "reviewer", a role on another chat-ui instance (ConversationGraphCheck).</li>
 * <li>{@code SCENARIO-T}: call {@code list_running_tools} and {@code search_docs}, which reach the AI-workspace and
 * html-saurus as nodes; the turn ends with {@code TOOLS-DONE} followed by their results (ToolNodesCheck).</li>
 * <li>The end-of-turn reminder check registers a reminder only for an answer that says "I will report back".</li>
 * <li>{@code SCENARIO-M}: {@code write} a file, {@code read} it back, and end with {@code NATIVE-DONE} followed by
 * what was read (McpServerCheck, OpenAiCompatStandardToolCalling_261120_oo01).</li>
 * <li>A revision request from html-saurus's Revise panel ({@code Review lines ...}): one proposal in the JSON form the
 * panel reads, quoting the first twelve characters of the text (RevisionPanel_261120_oo01).</li>
 * <li>Anything else: "OK. I will report back in 12 minutes."</li>
 * </ul>
 * When the request carries {@code tools}, as the openai-compat provider sends them, a scripted call is sent as
 * {@code tool_calls} rather than as {@code <invoke>} text, and tool results that end the request are read as
 * {@code Tool result (<name>):} text, so every scenario runs either way.
 * <ul>
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

    static final String GRAPH_G3 = """
            <function_calls>
            <invoke name="set_collaborator">
            <parameter name="chatId">02</parameter>
            <parameter name="role">translator</parameter>
            <parameter name="collaboratorChatId">project2/01</parameter>
            <reason>a role played by a conversation of another project</reason>
            </invoke>
            </function_calls>""";

    static final String GRAPH_G4 = """
            <function_calls>
            <invoke name="set_collaborator">
            <parameter name="chatId">03</parameter>
            <parameter name="role">reviewer</parameter>
            <parameter name="collaboratorChatId">01</parameter>
            <parameter name="node">b</parameter>
            <reason>a role played by a conversation of another chat-ui instance</reason>
            </invoke>
            </function_calls>""";

    static final String TOOLS_T = """
            <function_calls>
            <invoke name="list_running_tools">
            <reason>see what the AI-workspace runs</reason>
            </invoke>
            <invoke name="search_docs">
            <parameter name="query">abstraction</parameter>
            <reason>search the documentation through the html-saurus node</reason>
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

    static final String WRITE_M = """
            <function_calls>
            <invoke name="write">
            <parameter name="path">native-e2e.txt</parameter>
            <parameter name="content">NATIVE-CONTENT-42</parameter>
            </invoke>
            </function_calls>""";

    static final String READ_M = """
            <function_calls>
            <invoke name="read">
            <parameter name="path">native-e2e.txt</parameter>
            </invoke>
            </function_calls>""";

    static final Pattern INVOKE = Pattern.compile("<invoke name=\"([^\"]+)\">(.*?)</invoke>", Pattern.DOTALL);
    static final Pattern PARAMETER = Pattern.compile("<parameter name=\"([^\"]+)\">(.*?)</parameter>", Pattern.DOTALL);

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
    static String answer(String last, String whole) throws Exception {
        if (last.contains("bookkeeping check")) {
            return last.contains("I will report back") ? "REGISTER 12 | \"I will report back\"" : "NONE | nothing promised";
        }
        if (last.contains("TASK-WORK")) {
            Thread.sleep(3_600_000);   // the long turn
            return "finished";
        }
        if (last.contains("HELLO-ONLY")) return "Hello.";
        if (last.contains("SCENARIO-A")) return CALLS_A;
        if (last.contains("SCENARIO-G4")) return GRAPH_G4;
        if (last.contains("SCENARIO-G3")) return GRAPH_G3;
        if (last.contains("SCENARIO-G2")) return GRAPH_G2;
        if (last.contains("SCENARIO-G")) return GRAPH_G;
        if (last.contains("SCENARIO-R")) return ASK_03;
        if (last.contains("SCENARIO-T")) return TOOLS_T;
        if (last.contains("TEXT (those lines):\n")) {
            String text = last.substring(last.indexOf("TEXT (those lines):\n") + "TEXT (those lines):\n".length());
            String quote = text.substring(0, Math.min(12, text.indexOf('\n') > 0 ? text.indexOf('\n') : text.length()));
            return "{\"proposals\":[{\"quote\":" + JSON.writeValueAsString(quote)
                    + ",\"problem\":\"REVIEWED-BY-CONVERSATION\",\"fix\":\"shorten it\"}]}";
        }
        if (last.contains("SCENARIO-M")) return WRITE_M;
        if (last.contains("Tool result (write)") && whole.contains("SCENARIO-M")) return READ_M;
        if (last.contains("Tool result (read)") && whole.contains("SCENARIO-M")) return "NATIVE-DONE\n" + last;
        // Echoes the tool results, so the check reads them in the final answer the conversation keeps.
        if (last.contains("Tool result (") && whole.contains("SCENARIO-T")) return "TOOLS-DONE\n" + last;
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
        // The tool results that end the request, written as the text the <invoke> path sends them in.
        StringBuilder results = new StringBuilder();
        for (int i = messages.size() - 1; i >= 0 && "tool".equals(messages.get(i).path("role").asText()); i--) {
            JsonNode m = messages.get(i);
            results.insert(0, "Tool result (" + m.path("name").asText() + "):\n" + text(m) + "\n\n");
        }
        if (results.length() > 0) last = results.toString();
        boolean byDefinition = req.has("tools");
        StringBuilder whole = new StringBuilder();
        for (JsonNode m : messages) whole.append(text(m)).append(' ');
        String reply = answer(last, whole.toString());
        if (byDefinition && reply.contains("<invoke") && req.path("stream").asBoolean(false)) {
            StringBuilder sse = new StringBuilder();
            Matcher m = INVOKE.matcher(reply);
            int index = 0;
            while (m.find()) {
                ObjectNode args = JSON.createObjectNode();
                Matcher p = PARAMETER.matcher(m.group(2));
                while (p.find()) args.put(p.group(1), p.group(2));
                ObjectNode d = JSON.createObjectNode();
                ObjectNode c = d.putArray("choices").addObject();
                c.put("index", 0);
                ObjectNode call = c.putObject("delta").putArray("tool_calls").addObject();
                call.put("index", index).put("id", "call_" + System.nanoTime() + "_" + index).put("type", "function");
                call.putObject("function").put("name", m.group(1)).put("arguments", JSON.writeValueAsString(args));
                c.putNull("finish_reason");
                sse.append("data: ").append(JSON.writeValueAsString(d)).append("\n\n");
                index++;
            }
            sse.append("data: ").append(chunk(null, "tool_calls")).append("\n\n");
            appendUsage(sse, req, messages.size());
            sse.append("data: [DONE]\n\n");
            send(ex, 200, "text/event-stream", sse.toString().getBytes(StandardCharsets.UTF_8));
        } else if (req.path("stream").asBoolean(false)) {
            StringBuilder sse = new StringBuilder();
            sse.append("data: ").append(chunk(reply, null)).append("\n\n");
            sse.append("data: ").append(chunk(null, "stop")).append("\n\n");
            appendUsage(sse, req, messages.size());
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

    /**
     * Ends a stream with the chunk that carries the request's token counts, as a server does when the request has
     * {@code stream_options.include_usage}: 1000 prompt tokens plus 100 for each message, so a turn's request is
     * larger than the turn before it by a known amount (the Tokens tab check).
     */
    static void appendUsage(StringBuilder sse, JsonNode req, int messageCount) throws Exception {
        if (!req.path("stream_options").path("include_usage").asBoolean(false)) return;
        ObjectNode d = JSON.createObjectNode();
        d.putArray("choices");
        int prompt = 1000 + 100 * messageCount;
        ObjectNode usage = d.putObject("usage");
        usage.put("prompt_tokens", prompt).put("completion_tokens", 5).put("total_tokens", prompt + 5);
        usage.putObject("prompt_tokens_details").put("cached_tokens", 0);
        sse.append("data: ").append(JSON.writeValueAsString(d)).append("\n\n");
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
