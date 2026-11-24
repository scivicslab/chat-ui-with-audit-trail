import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * agent-connector-kernel's LLM proxy mounted on a throw-away chat-ui's Quarkus router at /llm/ (tasks/todo.md, step
 * 4a.2), reached over TCP and through the domain socket. An E2E program with main(), outside the Maven build
 * (TestingStandard_260404_oo01).
 *
 * <pre>
 * java e2e/LlmProxyCheck.java &lt;port&gt; &lt;socket path&gt;
 * </pre>
 *
 * The instance runs with {@code -Dagent-connector-kernel.llm.upstream=...} and the domain socket on. No scope is
 * registered in chat-ui, so every request is refused, and the upstream is never reached. Checks: a request with a
 * throw-away key gets 401 with a JSON body, over TCP and through the socket alike; a request naming no scope gets 400;
 * a path outside /llm/ is not answered by the proxy. Prints one line per check and exits 1 when any fails.
 */
public class LlmProxyCheck {

    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String base = "http://127.0.0.1:" + args[0];
        Path socket = Path.of(args[1]);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<String> tcp = client.send(HttpRequest.newBuilder(URI.create(base + "/llm/s1/v1/chat/completions"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer dummy-throw-away-key")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        check(tcp.statusCode() == 401 && tcp.body().contains("\"error\""), "over TCP a wrong key gets 401 with a JSON body",
                tcp.statusCode() + " " + tcp.body());
        HttpResponse<String> noScope = client.send(HttpRequest.newBuilder(URI.create(base + "/llm/"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        check(noScope.statusCode() == 400, "a request that names no scope gets 400", noScope.statusCode() + " " + noScope.body());
        String body = "{}";
        String raw = "POST /llm/s1/v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                + "Authorization: Bearer dummy-throw-away-key\r\nContent-Length: " + body.length()
                + "\r\nConnection: close\r\n\r\n" + body;
        String answer;
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            channel.write(ByteBuffer.wrap(raw.getBytes(StandardCharsets.UTF_8)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                out.write(buffer.array(), 0, buffer.limit());
                buffer.clear();
            }
            answer = out.toString(StandardCharsets.UTF_8);
        }
        check(answer.startsWith("HTTP/1.1 401") && answer.endsWith(tcp.body()),
                "through the socket the same request gets the same 401 and body", answer.length() > 300 ? answer.substring(0, 300) : answer);
        HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create(base + "/q/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        check(health.statusCode() == 200, "a path outside /llm/ is not answered by the proxy", health.statusCode() + "");
        System.out.println(failures.isEmpty() ? "ALL PASSED" : failures.size() + " FAILED: " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    static void check(boolean ok, String what, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : " -- " + detail));
        if (!ok) failures.add(what);
    }
}
