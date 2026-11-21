import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.confine.ClaudeConfinement;
import com.scivicslab.chatui.llm.LlmProxy;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.mcp.LocalMcpServer;
import com.scivicslab.chatui.mcp.McpRequestHandler;
import com.scivicslab.chatui.tool.DocsLookup;
import com.scivicslab.chatui.tool.ToolCallRecorder;
import com.scivicslab.chatui.tool.ToolExtension;
import com.scivicslab.chatui.tool.ToolRunner;
import com.scivicslab.chatui.tool.ToolScope;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The native-image probe of agent-connector-kernel (tasks/todo.md, stage 8). Outside the Maven build, per
 * TestingStandard_260404_oo01: compile it against agent-connector-kernel's jar and its dependencies, build it with
 * GraalVM's native-image, and run the executable.
 *
 * <pre>
 * java -cp &lt;classpath&gt; e2e/KernelNativeProbe.java        (on the JVM)
 * native-image -cp &lt;classpath&gt;:. KernelNativeProbe probe && ./probe   (natively)
 * </pre>
 *
 * It builds a ToolRunner over a temporary directory, lists the core tools, calls write and then read, and then starts
 * the localhost HTTP MCP server and sends it one tools/call with the right key and one with a wrong key. Then it
 * sends one request through the LLM proxy to a throw-away upstream with a dummy key, and builds Claude Code's
 * confinement arguments. Last it starts the MCP server on a Unix domain socket and sends it one tools/list with the
 * right key and one with a wrong key (stage 9 part 1). calc is
 * given an arithmetic-only evaluator, since JShell does not run in a native image. Prints one line per check and
 * exits 1 when any fails.
 */
public class KernelNativeProbe {

    static int failures = 0;

    static void check(boolean ok, String what, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : ": " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("kernel-probe");
        ToolRunner runner = new ToolRunner(new ToolScope("probe", new FileAccessScope(root, List.of()), ToolSet.FULL, Map.of()),
                expression -> expression.equals("23*47") ? "1081" : "error: this probe evaluates only 23*47",
                DocsLookup.HTTP, ToolExtension.NONE, ToolCallRecorder.NONE);

        List<String> core = List.copyOf(ToolCatalog.coreTools().keySet());
        check(core.equals(List.of("read", "calc", "search_docs", "list_references", "write", "remove")),
                "the core tools are listed", core.toString());
        String wrote = runner.run("write", "{\"path\":\"probe.txt\",\"content\":\"NATIVE-PROBE\"}", false);
        check(wrote.startsWith("wrote "), "write runs", wrote);
        String read = runner.run("read", "{\"path\":\"probe.txt\"}", false);
        check(read.contains("NATIVE-PROBE"), "read returns what write wrote", read);
        check(runner.run("calc", "{\"expression\":\"23*47\"}", false).equals("1081"), "calc runs on the given evaluator", "");

        ActorSystem system = new ActorSystem("kernel-probe");
        try (LocalMcpServer server = new LocalMcpServer(system, 0)) {
            check(server.address().getAddress().isLoopbackAddress(), "the MCP server is bound to the loopback address",
                    server.address().toString());
            String key = server.register(runner);
            String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"read\",\"arguments\":{\"path\":\"probe.txt\"}}}";
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> ok = client.send(post(server.url(), "probe", "Bearer " + key, call),
                    HttpResponse.BodyHandlers.ofString());
            check(ok.statusCode() == 200 && ok.body().contains("NATIVE-PROBE"), "the MCP server answers a request with the key",
                    ok.statusCode() + " " + ok.body());
            HttpResponse<String> refused = client.send(post(server.url(), "probe", "Bearer wrong", call),
                    HttpResponse.BodyHandlers.ofString());
            check(refused.statusCode() == 401, "the MCP server refuses a wrong key", refused.statusCode() + " " + refused.body());

            AtomicReference<String> seen = new AtomicReference<>();
            HttpServer upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            upstream.createContext("/v1/messages", exchange -> {
                seen.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                byte[] body = "data: {\"type\":\"message_stop\"}\n\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            upstream.start();
            try (LlmProxy proxy = new LlmProxy(system, 0, URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                    "x-api-key", "dummy-real-credential")) {
                String throwaway = proxy.register("probe");
                HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(proxy.baseUrl("probe") + "/v1/messages"))
                        .header("x-api-key", throwaway).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                check(answer.statusCode() == 200 && answer.body().contains("message_stop")
                                && "dummy-real-credential".equals(seen.get()),
                        "the LLM proxy forwards with the real credential in place of the throw-away key",
                        answer.statusCode() + " " + answer.body() + " upstream saw " + seen.get());
            } finally {
                upstream.stop(0);
            }
            List<String> confinement = new ClaudeConfinement("/probe/agent-connector-kernel").arguments("{}");
            check(confinement.get(0).equals("--settings") && confinement.get(1).contains("\"failIfUnavailable\":true")
                            && confinement.contains("--strict-mcp-config"),
                    "the confinement arguments are built", confinement.toString());

            Path socket = Files.createTempDirectory("kernel-probe-sock").resolve("run").resolve("mcp.sock");
            try (LocalMcpServer unix = new LocalMcpServer(system, socket)) {
                String unixKey = unix.register(new ToolRunner(new ToolScope("probe-unix",
                        new FileAccessScope(root, List.of()), ToolSet.FULL, Map.of()),
                        expression -> "1081", DocsLookup.HTTP, ToolExtension.NONE, ToolCallRecorder.NONE));
                String list = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
                String okAnswer = overSocket(socket, "Bearer " + unixKey, "probe-unix", list);
                check(okAnswer.startsWith("HTTP/1.1 200") && okAnswer.contains("\"read\""),
                        "the MCP server on a Unix socket lists the tools with the key", okAnswer);
                String refusedAnswer = overSocket(socket, "Bearer wrong", "probe-unix", list);
                check(refusedAnswer.startsWith("HTTP/1.1 401"), "the MCP server on a Unix socket refuses a wrong key",
                        refusedAnswer);
                String perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(socket));
                check(perms.equals("rw-------"), "the socket file is owner-only", perms);
            }
        } finally {
            system.terminate();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Sends one HTTP/1.1 POST /mcp over a Unix domain socket and returns the whole answer. */
    static String overSocket(Path socket, String authorization, String scope, String body) throws Exception {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        String head = "POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nAuthorization: "
                + authorization + "\r\n" + McpRequestHandler.SCOPE_HEADER + ": " + scope + "\r\nContent-Length: "
                + content.length + "\r\n\r\n";
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            var out = Channels.newOutputStream(channel);
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(content);
            out.flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            Channels.newInputStream(channel).transferTo(all);
            return all.toString(StandardCharsets.UTF_8);
        }
    }

    static HttpRequest post(String url, String scope, String authorization, String body) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .header(McpRequestHandler.SCOPE_HEADER, scope).header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
