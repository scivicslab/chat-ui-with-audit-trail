import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * The Quarkus HTTP server of a throw-away chat-ui instance on a Unix domain socket (tasks/todo.md, Decisions 10 and 12,
 * step 3; https://quarkus.io/guides/vertx-reference#use-a-unix-domain-socket). An E2E program with main(), outside the
 * Maven build (TestingStandard_260404_oo01).
 *
 * <pre>
 * java e2e/DomainSocketCheck.java &lt;socket path&gt;
 * </pre>
 *
 * The instance runs with {@code -Dquarkus.http.domain-socket=<path> -Dquarkus.http.domain-socket-enabled=true}. Checks:
 * the socket is 0600 and its directory 0700; an MCP {@code initialize} through the socket answers 200 with a complete
 * serverInfo. Prints one line per check and exits 1 when any fails.
 */
public class DomainSocketCheck {

    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path socket = Path.of(args[0]);
        String dirPerms = PosixFilePermissions.toString(Files.getPosixFilePermissions(socket.getParent()));
        String sockPerms = PosixFilePermissions.toString(Files.getPosixFilePermissions(socket));
        check(dirPerms.equals("rwx------"), "the socket's directory is 0700", dirPerms);
        check(sockPerms.equals("rw-------"), "the socket is 0600", sockPerms);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"domain-socket-check\",\"version\":\"1\"}}}";
        String request = "POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                + "Accept: application/json, text/event-stream\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\nConnection: close\r\n\r\n" + body;
        String answer;
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
            answer = out.toString(StandardCharsets.UTF_8);
        }
        check(answer.startsWith("HTTP/1.1 200"), "initialize through the socket answers 200", head(answer));
        check(answer.contains("\"serverInfo\"") && answer.matches("(?s).*\"serverInfo\"\\s*:\\s*\\{[^}]*\"version\"\\s*:\\s*\"[^\"]+\".*"),
                "the answer carries a complete serverInfo", head(answer));
        System.out.println(failures.isEmpty() ? "ALL PASSED" : failures.size() + " FAILED: " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    static String head(String s) {
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    static void check(boolean ok, String what, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : " -- " + detail));
        if (!ok) failures.add(what);
    }
}
