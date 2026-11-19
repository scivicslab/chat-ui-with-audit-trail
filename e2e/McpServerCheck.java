import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 1 of HarnessToolsThroughOneMcpServer_261120_oo01, end to end (ToolCatalogAndChatUiMcpServer_261120_oo01): an
 * MCP client sends stateless requests to chat-ui's {@code /mcp} naming a conversation in
 * {@code X-Chat-Ui-Conversation}, lists that conversation's tools, calls {@code write} and {@code read}, and each call
 * appears once in the conversation's I/O log.
 *
 * <p>Outside the Maven build, on a throwaway instance whose model is {@code e2e/ScriptedModelServer.java}. The check
 * first runs one turn of {@code project1/chat-01}, so that the conversation has an I/O-log session to record into.</p>
 *
 * <pre>
 * java e2e/McpServerCheck.java &lt;chat-ui HTTP port&gt;
 * </pre>
 *
 * Prints one line per check and exits 1 when any fails.
 */
public class McpServerCheck {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final String VERSION = "2026-07-28";
    static final String CONTENT = "MCP-E2E-CONTENT-" + System.currentTimeMillis();
    static int failures = 0;
    static int nextId = 1;

    static String get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static String post(String url, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    /**
     * Sends one stateless MCP request.
     *
     * @param conversation the {@code X-Chat-Ui-Conversation} value, or {@code null} to send none
     * @param tool         the tool of a {@code tools/call}, or {@code null} for {@code tools/list}
     */
    static String mcp(String base, String conversation, String tool, String argumentsJson) throws Exception {
        String method = tool == null ? "tools/list" : "tools/call";
        String meta = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + VERSION
                + "\",\"io.modelcontextprotocol/clientCapabilities\":{}}";
        String params = tool == null ? "{" + meta + "}"
                : "{\"name\":\"" + tool + "\",\"arguments\":" + argumentsJson + "," + meta + "}";
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + (nextId++) + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/mcp")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Protocol-Version", VERSION)
                .header("Mcp-Method", method);
        if (tool != null) b.header("Mcp-Name", tool);
        if (conversation != null) b.header("X-Chat-Ui-Conversation", conversation);
        HttpResponse<String> r = HTTP.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return r.statusCode() + " " + r.body();
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
        String chat = base + "/api/projects/project1/chats/01";

        // One turn, so the conversation has an I/O-log session and a turn number to label the calls with.
        System.out.println("POST chat: " + post(chat + "/chat", "{\"text\":\"hello\"}"));
        String status = "";
        for (int i = 0; i < 60; i++) {
            Thread.sleep(1000);
            status = get(chat + "/status");
            if (status.contains("\"busy\":false") && i > 2) break;
        }
        check(status.contains("\"busy\":false"), "the first turn ended", status);

        String list = mcp(base, "project1/chat-01", null, null);
        System.out.println("tools/list: " + list.substring(0, Math.min(400, list.length())) + " ...");
        check(list.startsWith("200 ") && list.contains("\"name\":\"read\""), "tools/list names read", list);
        check(list.contains("\"name\":\"list_chats\""), "tools/list names list_chats", list);
        for (String driving : new String[] {"ask_person", "ask_chat", "ask_chat_urgent", "accept_reply",
                "cancel_request", "expect_report"}) {
            check(!list.contains("\"name\":\"" + driving + "\""), "tools/list leaves out " + driving, list);
        }
        check(list.contains("\"required\":[\"path\"]"), "read's input schema requires path", list);

        String none = mcp(base, null, null, null);
        check(!none.contains("\"name\":\"read\""), "tools/list without the header lists no tool", none);
        String unknown = mcp(base, "project1/chat-99", null, null);
        check(!unknown.contains("\"name\":\"read\""), "tools/list naming no conversation that exists lists no tool", unknown);

        String write = mcp(base, "project1/chat-01", "write",
                "{\"path\":\"mcp-e2e.txt\",\"content\":\"" + CONTENT + "\"}");
        System.out.println("tools/call write: " + write);
        check(write.contains("\"isError\":false"), "tools/call write succeeds", write);
        String read = mcp(base, "project1/chat-01", "read", "{\"path\":\"mcp-e2e.txt\"}");
        System.out.println("tools/call read: " + read.substring(0, Math.min(400, read.length())));
        check(read.contains("\"isError\":false") && read.contains(CONTENT), "tools/call read returns what write wrote", read);

        String noHeader = mcp(base, null, "read", "{\"path\":\"mcp-e2e.txt\"}");
        System.out.println("tools/call without header: " + noHeader);
        check(!noHeader.contains(CONTENT) && (noHeader.contains("\"isError\":true") || noHeader.contains("\"error\"")),
                "tools/call without the header is an error", noHeader);
        String driving = mcp(base, "project1/chat-01", "ask_chat", "{\"chatId\":\"02\",\"prompt\":\"hi\"}");
        System.out.println("tools/call ask_chat: " + driving);
        check(driving.contains("\"isError\":true") || driving.contains("\"error\""), "tools/call ask_chat is an error", driving);

        // Each call is in the I/O log once: the write's input and the read's observation hold the text.
        String sessions = get(base + "/api/sessions?conversationSquadId=project1/chat-01");
        Matcher m = Pattern.compile("\"sessionId\"\\s*:\\s*(\\d+)").matcher(sessions);
        check(m.find(), "the conversation has an I/O-log session", sessions);
        // The turn this check ran is the session's last; earlier runs on the same instance left turns before it.
        String trace = "";
        for (int turn = 1; m.find(0) && turn <= 50; turn++) {
            String t = get(base + "/api/sessions/" + m.group(1) + "/trace/" + turn);
            if (!t.contains("\"steps\"")) break;
            trace += t;
        }
        check(count(trace, CONTENT) == 2, "the write and the read are each recorded once (" + count(trace, CONTENT)
                + " occurrences)", trace);
        check(trace.contains("mcp-e2e.txt"), "the trace names the file", trace);

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
