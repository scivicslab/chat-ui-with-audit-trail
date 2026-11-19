import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The E2E test of OpenAiCompatStandardToolCalling_261120_oo01 with real models: each model is asked a question it
 * can answer only by calling {@code read}. The tool must run, the answer must hold what the file holds, and the I/O
 * log must record no {@code turnN/stepM/repair}.
 *
 * <p>Outside the Maven build, on a throwaway instance started with {@code -Dchat-ui.servers=} naming the inference
 * servers. Each model gets a conversation of its own (chat-11, chat-12, ...). The file is written through the
 * instance's MCP server, into that conversation's working directory, so only {@code read} can see it.</p>
 *
 * <pre>
 * java e2e/RealModelToolCallingCheck.java &lt;chat-ui HTTP port&gt; &lt;model&gt; [&lt;model&gt; ...]
 * </pre>
 *
 * Prints one line per check and exits 1 when any fails.
 */
public class RealModelToolCallingCheck {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static int failures = 0;

    static String get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static String post(String url, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    /** One stateless {@code tools/call} to the instance's MCP server, as the conversation's tool. */
    static String mcpCall(String base, String conversation, String tool, String argumentsJson) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + argumentsJson + ",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":"
                + "\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/mcp")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("Mcp-Protocol-Version", "2026-07-28").header("Mcp-Method", "tools/call").header("Mcp-Name", tool)
                .header("X-Chat-Ui-Conversation", conversation)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static void check(boolean ok, String what, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : ": " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        String base = "http://127.0.0.1:" + args[0];
        for (int i = 1; i < args.length; i++) {
            String model = args[i];
            String id = String.valueOf(10 + i);
            String chat = base + "/api/projects/project1/chats/" + id;
            String code = "KESTREL-" + (1000 + (int) (Math.random() * 9000));
            String file = "secret-" + id + ".txt";
            System.out.println("=== " + model + " in project1/chat-" + id);
            post(base + "/api/projects/project1/chats/" + id, "{}");
            System.out.println("model: " + post(chat + "/model", "{\"model\":\"" + model + "\"}"));
            System.out.println("plant: " + mcpCall(base, "project1/chat-" + id, "write",
                    "{\"path\":\"" + file + "\",\"content\":\"The access code is " + code + ".\"}"));
            post(chat + "/chat", "{\"text\":\"What access code is written in the file " + file
                    + "? Read the file, then answer with the code alone.\"}");
            String status = "";
            for (int s = 0; s < 600; s++) {
                Thread.sleep(1000);
                status = get(chat + "/status");
                if (status.contains("\"busy\":false") && s > 2) break;
            }
            check(status.contains("\"busy\":false"), model + ": the turn ended", status);

            String messages = get(chat + "/request-messages");
            int lastAssistant = messages.lastIndexOf("\"role\":\"assistant\"");
            String tail = lastAssistant < 0 ? "" : messages.substring(Math.max(0, lastAssistant - 400));
            System.out.println("history tail: " + tail.substring(0, Math.min(600, tail.length())));
            check(messages.contains("\"role\":\"tool\"") && messages.contains("\"name\":\"read\""),
                    model + ": read was called and its result kept", messages);
            check(tail.contains(code), model + ": the answer holds the code " + code, tail);

            String sessions = get(base + "/api/sessions?conversationSquadId=project1/chat-" + id);
            Matcher m = Pattern.compile("\"sessionId\"\\s*:\\s*(\\d+)").matcher(sessions);
            String trace = m.find() ? get(base + "/api/sessions/" + m.group(1) + "/trace/1") : "";
            check(trace.contains("\"toolName\":\"read\""), model + ": read is in the I/O log", trace);
            check(!trace.contains("/repair"), model + ": no call was repaired", trace);
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
