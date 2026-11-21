import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * agent-connector-kernel's MCP server on Quarkiverse quarkus-mcp-server, end to end against a throw-away chat-ui
 * instance (tasks/todo.md, Decisions 12 and 13, step 2). An E2E program with main(), outside the Maven build
 * (TestingStandard_260404_oo01).
 *
 * <pre>
 * java e2e/McpKeyCheck.java &lt;port&gt; no-key      # the instance runs as shipped: require-key=false
 * java e2e/McpKeyCheck.java &lt;port&gt; key-required # the instance runs with -Dagent-connector-kernel.mcp.require-key=true
 * </pre>
 *
 * <ul>
 * <li>no-key: initialize answers 200 with a complete serverInfo (name and version), with no key and no conversation.</li>
 * <li>key-required: chat-ui registers no key, so every MCP request is refused. A request that names no conversation
 * gets 400. A known conversation with no key, a known conversation with a wrong key, and an unknown conversation all
 * get the same 401 with the same body. A path outside the MCP server is not affected.</li>
 * </ul>
 * Prints one line per check and exits 1 when any fails.
 */
public class McpKeyCheck {

    static final String HEADER = "X-Chat-Ui-Conversation";
    static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
            + "\"capabilities\":{},\"clientInfo\":{\"name\":\"mcp-key-check\",\"version\":\"1\"}}}";
    static final List<String> failures = new ArrayList<>();
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        String base = "http://127.0.0.1:" + args[0];
        String mode = args[1];
        if (mode.equals("no-key")) {
            HttpResponse<String> init = post(base, null, null);
            check(init.statusCode() == 200, "initialize without a key answers 200", init.statusCode() + " " + init.body());
            String body = init.body();
            check(body.contains("\"serverInfo\"") && body.matches("(?s).*\"serverInfo\"\\s*:\\s*\\{[^}]*\"name\".*")
                    && body.matches("(?s).*\"serverInfo\"\\s*:\\s*\\{[^}]*\"version\"\\s*:\\s*\"[^\"]+\".*"),
                    "serverInfo has a name and a version", body);
        } else {
            HttpResponse<String> noScope = post(base, null, "Bearer dummy");
            check(noScope.statusCode() == 400, "a request that names no conversation gets 400", describe(noScope));
            HttpResponse<String> noKey = post(base, "project1/chat-01", null);
            HttpResponse<String> wrongKey = post(base, "project1/chat-01", "Bearer dummy-wrong-key");
            HttpResponse<String> unknown = post(base, "project1/chat-99", "Bearer dummy-wrong-key");
            check(noKey.statusCode() == 401, "a known conversation with no key gets 401", describe(noKey));
            check(wrongKey.statusCode() == 401, "a known conversation with a wrong key gets 401", describe(wrongKey));
            check(unknown.statusCode() == 401, "an unknown conversation gets 401", describe(unknown));
            check(unknown.body().equals(wrongKey.body()) && unknown.body().equals(noKey.body()),
                    "the three 401s have the same body", "[" + noKey.body() + "] [" + wrongKey.body() + "] [" + unknown.body() + "]");
            HttpResponse<String> other = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/q/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            check(other.statusCode() == 200, "a path outside the MCP server is not refused", describe(other));
        }
        System.out.println(failures.isEmpty() ? "ALL PASSED" : failures.size() + " FAILED: " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    static HttpResponse<String> post(String base, String conversation, String authorization) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INIT));
        if (conversation != null) b.header(HEADER, conversation);
        if (authorization != null) b.header("Authorization", authorization);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String describe(HttpResponse<String> r) {
        return r.statusCode() + " [" + (r.body().length() > 200 ? r.body().substring(0, 200) : r.body()) + "]";
    }

    static void check(boolean ok, String what, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : " -- " + detail));
        if (!ok) failures.add(what);
    }
}
