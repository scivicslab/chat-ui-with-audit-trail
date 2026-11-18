import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Step 7 of ActorNodesForWorkspaceAndPortal_261118_oo01, end to end: an AI-workspace runs an html-saurus portal and a
 * chat-ui instance, all built from v5.x; both announce themselves; a chat-ui conversation calls
 * {@code list_running_tools} and {@code search_docs}, which reach the AI-workspace and the portal as nodes.
 *
 * <p>Outside the Maven build, on a throwaway setup. chat-ui talks to {@code e2e/ScriptedModelServer.java}, whose
 * {@code SCENARIO-T} makes the two tool calls.</p>
 *
 * <pre>
 * java e2e/ToolNodesCheck.java &lt;AI-workspace HTTP port&gt; &lt;chat-ui HTTP port&gt;
 * </pre>
 *
 * Prints one line per check and exits 1 when any fails. Whether the calls went through the nodes rather than the
 * HTTP fallback is read from the AI-workspace's and the portal's logs by the script that runs this.
 */
public class ToolNodesCheck {

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

    public static void main(String[] args) throws Exception {
        String ws = "http://127.0.0.1:" + args[0];
        String chat = "http://127.0.0.1:" + args[1] + "/api/projects/project1/chats/01";

        String nodes = "";
        for (int i = 0; i < 90; i++) {
            nodes = get(ws + "/api/nodes");
            if (nodes.contains("\"kind\":\"html-saurus\"") && nodes.contains("\"kind\":\"chat-ui\"")) break;
            Thread.sleep(1000);
        }
        System.out.println("GET /api/nodes: " + nodes);
        check(nodes.contains("\"kind\":\"ai-workspace\""), "/api/nodes lists the AI-workspace", nodes);
        check(nodes.contains("\"kind\":\"html-saurus\""), "/api/nodes lists the announced html-saurus portal", nodes);
        check(nodes.contains("\"kind\":\"chat-ui\""), "/api/nodes lists the announced chat-ui instance", nodes);

        // chat-ui reads /api/nodes every 60 seconds and when it starts; wait for it to know the portal.
        Thread.sleep(65_000);
        System.out.println("POST chat: " + post(chat + "/chat", "{\"text\":\"SCENARIO-T\"}"));
        String status = "";
        for (int i = 0; i < 120; i++) {
            Thread.sleep(1000);
            status = get(chat + "/status");
            if (status.contains("\"busy\":false") && i > 2) break;
        }
        String messages = get(chat + "/request-messages");
        int lr = messages.indexOf("Tool result (list_running_tools)");
        int sd = messages.indexOf("Tool result (search_docs)");
        String around = lr >= 0 ? messages.substring(lr, Math.min(messages.length(), lr + 900)) : messages;
        System.out.println("--- list_running_tools result\n" + around.replace("\\n", "\n"));
        String around2 = sd >= 0 ? messages.substring(sd, Math.min(messages.length(), sd + 700)) : "";
        System.out.println("--- search_docs result\n" + around2.replace("\\n", "\n"));
        check(lr >= 0, "the conversation called list_running_tools", status);
        check(lr >= 0 && around.contains("html-saurus") && around.contains("READY") && !around.contains("error:"),
                "list_running_tools lists the running html-saurus as READY", around);
        check(sd >= 0, "the conversation called search_docs", status);
        check(sd >= 0 && around2.contains("candidate documents"), "search_docs found documents", around2);
        check(messages.contains("TOOLS-DONE"), "the turn ended after the tool results", status);
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
