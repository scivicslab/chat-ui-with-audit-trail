import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.confine.CodexHarness;
import com.scivicslab.chatui.confine.HermesHarness;
import com.scivicslab.chatui.confine.OpenCodeHarness;
import com.scivicslab.chatui.confine.PodmanConfinement;
import com.scivicslab.chatui.confine.PodmanHarness;
import com.scivicslab.chatui.llm.LlmProxy;
import com.scivicslab.chatui.mcp.LocalMcpServer;
import com.scivicslab.chatui.tool.DocsLookup;
import com.scivicslab.chatui.tool.ToolCallRecorder;
import com.scivicslab.chatui.tool.ToolExtension;
import com.scivicslab.chatui.tool.ToolRunner;
import com.scivicslab.chatui.tool.ToolScope;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Podman confinement proof of stage 9 part 2 (tasks/todo.md; HarnessConfinement_261121_oo01). It is an E2E program
 * outside the Maven build (TestingStandard_260404_oo01), run on a host with rootless Podman.
 *
 * <pre>
 * java -cp &lt;agent-connector-kernel jar and its dependencies&gt; e2e/ConfinementCheck.java &lt;codex|opencode|hermes&gt; &lt;image&gt; &lt;run dir&gt;
 * </pre>
 *
 * On the host it starts agent-connector-kernel's MCP server and LLM proxy on Unix sockets only, a stub upstream for
 * the proxy and a stub "host loopback" server, both on host 127.0.0.1, and plants a marker file in a stand-in for
 * agent-connector-kernel's directory and in ~/.ssh. It then runs the container with exactly the arguments
 * {@link PodmanConfinement#arguments} builds for the harness, except that the trailing harness command is replaced by
 * a check script. The script checks, inside the container: the two sockets work through the relay; external TCP, DNS
 * and the host's ports fail; the markers cannot be read; the real key is nowhere, and the throw-away key is where the
 * harness expects it. Every key is a dummy. Prints one line per check, with the command behind it, and exits 1 when
 * any fails.
 *
 * <p>The script never carries the secret strings: it searches for them by a prefix it assembles from two halves, so
 * the strings do not reach the container even through its own command line.</p>
 */
public class ConfinementCheck {

    static final String REAL_PREFIX = "ACKREALDUMMY";
    static final String MARKER_PREFIX = "ACKSECRETMARKER";

    static final String SCRIPT = String.join("\n",
            "R=ACKREAL; R=${R}DUMMY; M=ACKSECRET; M=${M}MARKER",
            "out() { echo \"CHECK|$1|$2|$3\"; }",
            "http() { printf '%s' \"$1\" | socat -T5 - TCP:127.0.0.1:$2 2>&1; }",
            "for i in 1 2 3 4 5 6 7 8 9 10; do socat -T1 /dev/null TCP:127.0.0.1:18765 2>/dev/null && break; sleep 0.5; done",
            "B='{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}'",
            "REQ=$(printf 'POST /mcp HTTP/1.1\\r\\nHost: x\\r\\nContent-Type: application/json\\r\\nAuthorization: Bearer %s\\r\\nX-Agent-Connector-Scope: check\\r\\nContent-Length: %d\\r\\nConnection: close\\r\\n\\r\\n%s' \"$ACK_MCP_KEY\" ${#B} \"$B\")",
            "A=$(http \"$REQ\" 18765); case \"$A\" in *'HTTP/1.1 200'*'\"read\"'*) out mcp-tools-list-through-relay PASS 'POST /mcp tools/list via socat TCP:127.0.0.1:18765';; *) out mcp-tools-list-through-relay FAIL \"$(echo $A | head -c 200)\";; esac",
            "B2='{\"model\":\"dummy-model\",\"messages\":[]}'",
            "REQ2=$(printf 'POST /check/v1/chat/completions HTTP/1.1\\r\\nHost: x\\r\\nContent-Type: application/json\\r\\nAuthorization: Bearer %s\\r\\nContent-Length: %d\\r\\nConnection: close\\r\\n\\r\\n%s' \"$ACK_LLM_KEY\" ${#B2} \"$B2\")",
            "A2=$(http \"$REQ2\" 18766); case \"$A2\" in *'HTTP/1.1 200'*stub-upstream*) out proxied-request-through-relay PASS 'POST /check/v1/chat/completions via socat TCP:127.0.0.1:18766';; *) out proxied-request-through-relay FAIL \"$(echo $A2 | head -c 200)\";; esac",
            "if socat -T3 /dev/null TCP:1.1.1.1:443 2>/dev/null; then out external-tcp FAIL 'connected to 1.1.1.1:443'; else out external-tcp PASS 'socat -T3 /dev/null TCP:1.1.1.1:443 fails'; fi",
            "if getent hosts example.com >/dev/null 2>&1; then out dns FAIL 'example.com resolved'; else out dns PASS 'getent hosts example.com fails'; fi",
            "if socat -T3 /dev/null TCP:127.0.0.1:$HOST_STUB_PORT 2>/dev/null; then out host-loopback-port FAIL \"connected to 127.0.0.1:$HOST_STUB_PORT\"; else out host-loopback-port PASS \"socat TCP:127.0.0.1:$HOST_STUB_PORT fails (host stub listens there)\"; fi",
            "HIT=; for ip in $HOST_IPS; do socat -T3 /dev/null TCP:$ip:$HOST_STUB_PORT 2>/dev/null && HIT=\"$HIT $ip\"; done; if [ -n \"$HIT\" ]; then out host-addresses-port FAIL \"connected to$HIT\"; else out host-addresses-port PASS \"socat TCP:<each host IPv4: $HOST_IPS>:$HOST_STUB_PORT fails\"; fi",
            "if socat -T3 /dev/null TCP:10.0.2.2:$HOST_STUB_PORT 2>/dev/null || socat -T3 /dev/null TCP:10.88.0.1:$HOST_STUB_PORT 2>/dev/null; then out host-gateway-port FAIL 'a host gateway address answered'; else out host-gateway-port PASS \"socat TCP:10.0.2.2 and TCP:10.88.0.1:$HOST_STUB_PORT fail\"; fi",
            "if cat \"$KERNEL_MARKER_PATH\" 2>/dev/null; then out kernel-dir-marker FAIL readable; else out kernel-dir-marker PASS \"cat $KERNEL_MARKER_PATH fails\"; fi",
            "if cat \"$SSH_MARKER_PATH\" 2>/dev/null; then out ssh-marker FAIL readable; else out ssh-marker PASS \"cat $SSH_MARKER_PATH fails\"; fi",
            "if grep -rsl \"$M\" / --exclude-dir=proc --exclude-dir=sys --exclude-dir=dev 2>/dev/null | head -1 | grep -q .; then out marker-anywhere FAIL \"$(grep -rsl \"$M\" / --exclude-dir=proc --exclude-dir=sys --exclude-dir=dev | head -3)\"; else out marker-anywhere PASS 'grep -rs <marker prefix> / (except proc, sys, dev) finds nothing'; fi",
            "if env | grep -q \"$R\"; then out real-key-env FAIL 'in env'; else out real-key-env PASS 'env | grep <real-key prefix> finds nothing'; fi",
            "if tr '\\0' '\\n' < /proc/1/environ | grep -q \"$R\"; then out real-key-proc1-environ FAIL 'in /proc/1/environ'; else out real-key-proc1-environ PASS 'tr \\\\0 \\\\n < /proc/1/environ | grep <real-key prefix> finds nothing'; fi",
            "if grep -rsl \"$R\" / --exclude-dir=proc --exclude-dir=sys --exclude-dir=dev 2>/dev/null | head -1 | grep -q .; then out real-key-files FAIL \"$(grep -rsl \"$R\" / --exclude-dir=proc --exclude-dir=sys --exclude-dir=dev | head -3)\"; else out real-key-files PASS 'grep -rs <real-key prefix> / (mounted files included) finds nothing'; fi",
            "echo \"THROWAWAY_SHA256|$(printf '%s' \"$ACK_LLM_KEY\" | sha256sum | cut -d' ' -f1)\"",
            "if grep -q ACK_LLM_KEY \"$CONFIG_PATH\"; then out config-names-throwaway-var PASS \"grep ACK_LLM_KEY $CONFIG_PATH\"; else out config-names-throwaway-var FAIL \"$CONFIG_PATH does not name ACK_LLM_KEY\"; fi",
            "");

    public static void main(String[] args) throws Exception {
        String which = args[0];
        String image = args[1];
        Path base = Path.of(args[2]).toAbsolutePath();
        PodmanHarness harness = switch (which) {
            case "codex" -> new CodexHarness();
            case "opencode" -> new OpenCodeHarness();
            case "hermes" -> new HermesHarness();
            default -> throw new IllegalArgumentException("codex, opencode or hermes");
        };
        String token = HexFormat.of().formatHex(SecureRandom.getInstanceStrong().generateSeed(8));
        String realKey = REAL_PREFIX + "-" + token;
        String marker = MARKER_PREFIX + "-" + token;

        Path kernelDir = base.resolve("kernel-dir");
        Files.createDirectories(kernelDir);
        Path kernelMarker = kernelDir.resolve("marker");
        Files.writeString(kernelMarker, marker + "\n");
        Path sshMarker = Path.of(System.getProperty("user.home"), ".ssh", "ack-confinement-marker-" + token);
        Files.writeString(sshMarker, marker + "\n");
        Path runtime = Path.of(System.getenv().getOrDefault("XDG_RUNTIME_DIR", base.toString()));
        Path socketDir = runtime.resolve("ack-check-" + token);
        Path workDir = base.resolve("work-" + which);
        Files.createDirectories(workDir);
        Path configFile = base.resolve("config-" + which + "-" + token);

        AtomicReference<String> upstreamSaw = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            upstreamSaw.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"stub\":\"stub-upstream\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        HttpServer hostStub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        hostStub.createContext("/", exchange -> {
            byte[] body = "HOST-LOOPBACK-REACHED".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        hostStub.start();

        ActorSystem system = new ActorSystem("confinement-check");
        int failures = 0;
        List<String> rows = new ArrayList<>();
        try (LocalMcpServer mcp = new LocalMcpServer(system, socketDir.resolve(PodmanConfinement.MCP_SOCKET));
             LlmProxy proxy = new LlmProxy(system, socketDir.resolve(PodmanConfinement.LLM_SOCKET),
                     URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), "authorization", "Bearer " + realKey)) {
            String mcpKey = mcp.register(new ToolRunner(new ToolScope("check", new FileAccessScope(workDir, List.of()),
                    ToolSet.FULL, Map.of()), expression -> "1081", DocsLookup.HTTP, ToolExtension.NONE, ToolCallRecorder.NONE));
            String llmKey = proxy.register("check");
            Files.writeString(configFile, harness.configText("check", "dummy-model"));

            PodmanConfinement.Launch launch = new PodmanConfinement.Launch(image, kernelDir, socketDir, workDir, configFile,
                    harness, mcpKey, llmKey);
            List<String> launcherArgs = PodmanConfinement.arguments(launch);
            List<String> run = new ArrayList<>(launcherArgs.subList(0, launcherArgs.size() - harness.command().size()));
            // The only change to the launcher's arguments: the check script runs where the harness would.
            int imageAt = run.indexOf(image);
            List<String> extraEnv = List.of("-e", "HOST_STUB_PORT=" + hostStub.getAddress().getPort(),
                    "-e", "HOST_IPS=" + String.join(" ", hostAddresses()), "-e", "KERNEL_MARKER_PATH=" + kernelMarker,
                    "-e", "SSH_MARKER_PATH=" + sshMarker, "-e", "CONFIG_PATH=" + harness.containerConfigPath());
            run.addAll(imageAt, extraEnv);
            run.addAll(List.of("sh", "-c", SCRIPT));
            List<String> command = new ArrayList<>(List.of("nice", "-n", "19"));
            command.addAll(run);
            System.out.println("launcher arguments: " + String.join(" ", launcherArgs));
            System.out.println("check adds -e HOST_STUB_PORT, HOST_IPS, KERNEL_MARKER_PATH, SSH_MARKER_PATH, CONFIG_PATH"
                    + " and runs the check script in place of: " + String.join(" ", harness.command()));

            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            pb.environment().putAll(PodmanConfinement.environment(launch));
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            p.getInputStream().transferTo(out);
            boolean ended = p.waitFor(10, TimeUnit.MINUTES);
            String output = out.toString(StandardCharsets.UTF_8);
            Map<String, String[]> checks = new LinkedHashMap<>();
            String throwawayHash = null;
            for (String line : output.split("\n")) {
                if (line.startsWith("CHECK|")) {
                    String[] f = line.split("\\|", 4);
                    checks.put(f[1], new String[] {f[2], f.length > 3 ? f[3] : ""});
                } else if (line.startsWith("THROWAWAY_SHA256|")) {
                    throwawayHash = line.substring("THROWAWAY_SHA256|".length()).strip();
                } else if (!line.isBlank()) {
                    System.out.println("container: " + line);
                }
            }
            if (!ended) checks.put("container-ended", new String[] {"FAIL", "did not end within 10 minutes"});
            String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(llmKey.getBytes(StandardCharsets.UTF_8)));
            checks.put("throwaway-key-in-env", expectedHash.equals(throwawayHash)
                    ? new String[] {"PASS", "sha256 of $ACK_LLM_KEY in the container = sha256 of the proxy's throw-away key"}
                    : new String[] {"FAIL", "container sha256 " + throwawayHash + " != " + expectedHash});
            checks.put("upstream-got-real-key", ("Bearer " + realKey).equals(upstreamSaw.get())
                    ? new String[] {"PASS", "the stub upstream received Authorization: Bearer <the dummy real key>"}
                    : new String[] {"FAIL", "the stub upstream received " + upstreamSaw.get()});
            for (String expected : List.of("mcp-tools-list-through-relay", "proxied-request-through-relay", "external-tcp",
                    "dns", "host-loopback-port", "host-addresses-port", "host-gateway-port", "kernel-dir-marker", "ssh-marker",
                    "marker-anywhere", "real-key-env", "real-key-proc1-environ", "real-key-files",
                    "config-names-throwaway-var")) {
                checks.putIfAbsent(expected, new String[] {"FAIL", "the script did not report it"});
            }
            for (Map.Entry<String, String[]> c : checks.entrySet()) {
                if (!"PASS".equals(c.getValue()[0])) failures++;
                rows.add("| " + which + " | " + c.getKey() + " | " + c.getValue()[0] + " | " + c.getValue()[1].replace("|", "/") + " |");
            }
            System.out.println("podman exit " + (ended ? p.exitValue() : "timeout"));
        } finally {
            upstream.stop(0);
            hostStub.stop(0);
            system.terminate();
            Files.deleteIfExists(sshMarker);
            Files.deleteIfExists(configFile);
        }
        rows.forEach(System.out::println);
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** @return every non-loopback IPv4 address of this host, for the host-port check */
    static List<String> hostAddresses() throws Exception {
        List<String> found = new ArrayList<>();
        for (var nic : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
            if (nic.isLoopback() || !nic.isUp()) continue;
            for (var addr : java.util.Collections.list(nic.getInetAddresses())) {
                if (addr instanceof java.net.Inet4Address) found.add(addr.getHostAddress());
            }
        }
        return found;
    }
}
