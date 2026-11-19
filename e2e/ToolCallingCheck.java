import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2 of HarnessToolsThroughOneMcpServer_261120_oo01, end to end (OpenAiCompatStandardToolCalling_261120_oo01):
 * an openai-compat conversation is given its tools as definitions, the model's {@code tool_calls} run through
 * chat-ui's MCP server while the conversation waits in {@code runTool}, the results go back as {@code tool}
 * messages, and the turn's calls stay in the history after it ends.
 *
 * <p>Outside the Maven build, on a throwaway instance whose model is {@code e2e/ScriptedModelServer.java}; its
 * {@code SCENARIO-M} writes a file, reads it back and ends with {@code NATIVE-DONE}.</p>
 *
 * <pre>
 * java e2e/ToolCallingCheck.java &lt;chat-ui HTTP port&gt;
 * </pre>
 *
 * Prints one line per check and exits 1 when any fails.
 */
public class ToolCallingCheck {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static int failures = 0;

    static String get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static String post(String url, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static void check(boolean ok, String what, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : ": " + detail));
        if (!ok) failures++;
    }

    static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) n++;
        return n;
    }

    public static void main(String[] args) throws Exception {
        String base = "http://127.0.0.1:" + args[0];
        String chat = base + "/api/projects/project1/chats/02";

        // A conversation of its own, so earlier checks on the same instance leave nothing in its history.
        System.out.println("create chat-02: " + post(base + "/api/projects/project1/chats/02", "{}"));
        System.out.println("POST chat: " + post(chat + "/chat", "{\"text\":\"SCENARIO-M\"}"));
        String status = "";
        for (int i = 0; i < 60; i++) {
            Thread.sleep(1000);
            status = get(chat + "/status");
            if (status.contains("\"busy\":false") && i > 2) break;
        }
        check(status.contains("\"busy\":false"), "the turn ended: the MCP calls did not wait on the conversation", status);

        String messages = get(chat + "/request-messages");
        System.out.println("request-messages: " + messages.substring(0, Math.min(1500, messages.length())) + " ...");
        java.util.List<String> roles = new java.util.ArrayList<>();
        Matcher r = Pattern.compile("\"role\":\"(\\w+)\"").matcher(messages);
        while (r.find()) roles.add(r.group(1));
        System.out.println("roles: " + roles);
        check(roles.equals(java.util.List.of("system", "user", "assistant", "tool", "assistant", "tool", "assistant")),
                "the history is the system message, the question, two calls with their results, and the answer", roles.toString());
        check(!messages.contains("<invoke name=\\\"calc\\\">"), "no call format is taught", messages);
        check(count(messages, "\"tool_calls\"") == 2, "both calls stay in the history after the turn", messages);
        check(count(messages, "\"role\":\"tool\"") == 2, "both results stay in the history after the turn", messages);
        check(messages.contains("NATIVE-DONE") && messages.contains("NATIVE-CONTENT-42"),
                "the answer was written from the read's result", messages);

        String sessions = get(base + "/api/sessions?conversationSquadId=project1/chat-02");
        Matcher m = Pattern.compile("\"sessionId\"\\s*:\\s*(\\d+)").matcher(sessions);
        String trace = m.find() ? get(base + "/api/sessions/" + m.group(1) + "/trace/1") : "";
        System.out.println("trace: " + trace.substring(0, Math.min(800, trace.length())) + " ...");
        check(count(trace, "\"toolName\":\"write\"") == 1, "write is in the I/O log once", trace);
        check(count(trace, "\"toolName\":\"read\"") == 1, "read is in the I/O log once", trace);
        check(!trace.contains("/repair"), "no call was repaired", trace);

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
