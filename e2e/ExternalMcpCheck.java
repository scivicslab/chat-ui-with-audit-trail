import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ExternalMcpServersOfAConversation_261120_oo01, end to end: a conversation is registered with an html-saurus
 * {@code /mcp} as {@code htmlsaurus}, and a real model answers a question it can answer only through
 * {@code htmlsaurus__search}. With {@code --restored}, it only checks that the registration came back after the
 * instance was started again.
 *
 * <p>Outside the Maven build, on a throwaway instance started with {@code -Dchat-ui.servers=} naming an inference
 * server whose model answers with {@code tool_calls}.</p>
 *
 * <pre>
 * java e2e/ExternalMcpCheck.java &lt;chat-ui HTTP port&gt; &lt;model&gt; &lt;html-saurus /mcp URL&gt;
 * java e2e/ExternalMcpCheck.java &lt;chat-ui HTTP port&gt; --restored &lt;html-saurus /mcp URL&gt;
 * </pre>
 *
 * Prints one line per check and exits 1 when any fails.
 */
public class ExternalMcpCheck {

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
        String chat = base + "/api/projects/project1/chats/21";
        String mcpUrl = args[2];

        if ("--restored".equals(args[1])) {
            String servers = get(chat + "/mcp-servers");
            System.out.println("GET mcp-servers after the restart: " + servers);
            check(servers.contains("\"htmlsaurus\"") && servers.contains(mcpUrl),
                    "the registration came back after the restart", servers);
        } else {
            String model = args[1];
            post(base + "/api/projects/project1/chats/21", "{}");
            System.out.println("model: " + post(chat + "/model", "{\"model\":\"" + model + "\"}"));
            String bad = post(chat + "/mcp-servers", "{\"servers\":{\"Html_Saurus\":\"" + mcpUrl + "\"}}");
            check(bad.contains("\"error\""), "a bad server name is refused", bad);
            String set = post(chat + "/mcp-servers", "{\"servers\":{\"htmlsaurus\":\"" + mcpUrl + "\"}}");
            System.out.println("register: " + set);
            check(set.contains("\"ok\""), "html-saurus is registered as htmlsaurus", set);
            String servers = get(chat + "/mcp-servers");
            check(servers.contains("\"htmlsaurus\""), "GET mcp-servers lists it", servers);

            post(chat + "/chat", "{\"text\":\"Search the team documentation with the htmlsaurus__search tool for"
                    + " SearchBeforeReading, then tell me the Path of the first result, exactly as the tool gives it.\"}");
            String status = "";
            for (int s = 0; s < 600; s++) {
                Thread.sleep(1000);
                status = get(chat + "/status");
                if (status.contains("\"busy\":false") && s > 2) break;
            }
            check(status.contains("\"busy\":false"), "the turn ended", status);

            String messages = get(chat + "/request-messages");
            int last = messages.lastIndexOf("\"role\":\"assistant\"");
            String tail = last < 0 ? "" : messages.substring(Math.max(0, last - 300));
            System.out.println("history tail: " + tail.substring(0, Math.min(700, tail.length())));
            check(messages.contains("\"name\":\"htmlsaurus__search\""), "htmlsaurus__search was called", messages);
            check(tail.contains("SearchBeforeReading_260923_oo01"), "the answer gives the path the search found", tail);

            String sessions = get(base + "/api/sessions?conversationSquadId=project1/chat-21");
            Matcher m = Pattern.compile("\"sessionId\"\\s*:\\s*(\\d+)").matcher(sessions);
            String trace = m.find() ? get(base + "/api/sessions/" + m.group(1) + "/trace/1") : "";
            check(count(trace, "\"toolName\":\"htmlsaurus__search\"") >= 1
                            && count(trace, "\"toolName\":\"htmlsaurus__search\"")
                            == count(messages, "\"name\":\"htmlsaurus__search\"") / 2,
                    "each htmlsaurus__search call is in the I/O log once", trace);
            check(!trace.contains("/repair"), "no call was repaired", trace);
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
