import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal MCP client over a Unix domain socket, HTTP/1.1 with one request per connection (tasks/todo.md, stage 11).
 * Used by SshConnectorCheck, which the source launcher compiles together with it.
 */
final class SocketMcpClient {

    static final String SCOPE_HEADER = "X-Agent-Connector-Scope";

    /** One HTTP answer: status, headers in lower case, and the body with any chunking undone. */
    record Answer(int status, Map<String, String> headers, String body) {

        /** @return the JSON-RPC message of the body: the body itself, or the data of its first event */
        String json() {
            if (body.startsWith("{")) return body;
            for (String line : body.split("\n")) {
                if (line.startsWith("data:")) return line.substring(5).strip();
            }
            return body;
        }

        @Override
        public String toString() {
            String b = body.length() > 400 ? body.substring(0, 400) + "..." : body;
            return status + " " + b;
        }
    }

    private final Path socket;
    private final String scope;
    private final String key;
    private String session;
    private int nextId = 1;

    SocketMcpClient(Path socket, String scope, String key) {
        this.socket = socket;
        this.scope = scope;
        this.key = key;
    }

    /** Sends initialize and notifications/initialized and keeps the session id. */
    Answer initialize() throws Exception {
        Answer a = post("{\"jsonrpc\":\"2.0\",\"id\":" + (nextId++) + ",\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"ssh-connector-check\","
                + "\"version\":\"1\"}}}");
        session = a.headers().get("mcp-session-id");
        if (a.status() == 200) post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        return a;
    }

    Answer listTools() throws Exception {
        return post("{\"jsonrpc\":\"2.0\",\"id\":" + (nextId++) + ",\"method\":\"tools/list\"}");
    }

    /** @param argumentsJson the call's arguments, a JSON object */
    Answer call(String tool, String argumentsJson) throws Exception {
        return post("{\"jsonrpc\":\"2.0\",\"id\":" + (nextId++) + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + argumentsJson + "}}");
    }

    Answer post(String body) throws Exception {
        StringBuilder req = new StringBuilder("POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                + "Accept: application/json, text/event-stream\r\nConnection: close\r\n");
        if (scope != null) req.append(SCOPE_HEADER).append(": ").append(scope).append("\r\n");
        if (key != null) req.append("Authorization: Bearer ").append(key).append("\r\n");
        if (session != null) req.append("Mcp-Session-Id: ").append(session).append("\r\n");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        req.append("Content-Length: ").append(bytes.length).append("\r\n\r\n").append(body);
        return send(req.toString());
    }

    private Answer send(String request) throws Exception {
        String raw;
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                out.write(buffer.array(), 0, buffer.limit());
                buffer.clear();
            }
            // Latin-1 keeps one char per byte, so chunk sizes count right; the body is decoded as UTF-8 at the end.
            raw = out.toString(StandardCharsets.ISO_8859_1);
        }
        int end = raw.indexOf("\r\n\r\n");
        String head = end < 0 ? raw : raw.substring(0, end);
        String body = end < 0 ? "" : raw.substring(end + 4);
        String[] lines = head.split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) headers.put(lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).strip());
        }
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) body = unchunk(body);
        body = new String(body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return new Answer(status, headers, body);
    }

    private static String unchunk(String body) {
        StringBuilder sb = new StringBuilder();
        int at = 0;
        while (at < body.length()) {
            int eol = body.indexOf("\r\n", at);
            if (eol < 0) break;
            int size = Integer.parseInt(body.substring(at, eol).split(";")[0].strip(), 16);
            if (size == 0) break;
            sb.append(body, eol + 2, Math.min(body.length(), eol + 2 + size));
            at = eol + 2 + size + 2;
        }
        return sb.toString();
    }
}
