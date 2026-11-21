import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ssh-connector end to end against a throw-away sshd container, never a real cluster (tasks/todo.md, "Stage 11 spec",
 * step 3). An E2E program with main(), outside the Maven build (TestingStandard_260404_oo01), run on a host with
 * rootless Podman; it is compiled with ConnectorProcess and SocketMcpClient, and runs on the ssh-connector jar's
 * classpath (org.json).
 *
 * <pre>
 * java -cp &lt;ssh-connector jar&gt; e2e/SshConnectorE2E.java &lt;ssh-connector jar&gt; &lt;run dir&gt; &lt;sshd image&gt; &lt;opencode image&gt;
 * </pre>
 *
 * <ol>
 * <li>A fresh dummy ed25519 client key and a fresh host key; the sshd container publishes its port on the host's
 * 127.0.0.1 only.</li>
 * <li>Each of the seven tools once, as a direct MCP call with the key, through ssh-connector with no harness. ssh-connector's
 * known_hosts starts empty and accept-new records the host key. The fake sbatch must receive the script exactly.</li>
 * <li>A new sshd container with a changed host key on the same port: the next call fails with
 * {@code Host key verification failed}.</li>
 * <li>One confined harness, OpenCode with a stub model, calls submit_job and then job_status through MCP.</li>
 * </ol>
 * Afterwards the sshd container is stopped (it runs with --rm) and the dummy keys are deleted. Prints one row per check
 * and exits 1 when any fails.
 */
public class SshConnectorE2E {

    static final Map<String, String[]> checks = new LinkedHashMap<>();
    static final String SCRIPT = "#!/bin/bash\n#SBATCH --time=00:01:00\n# quotes ' \" and $(not run) stay as written\n"
            + "echo \"it's $(hostname)\" > out.txt\n";
    static final String HARNESS_SCRIPT = "#!/bin/bash\n#SBATCH --time=00:02:00\necho 'submitted by the harness'\n";
    static final String PROMPT = "Submit a batch job with the submit_job tool, then report its state with job_status.";

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path base = Path.of(args[1]).toAbsolutePath();
        String sshdImage = args[2];
        String harnessImage = args[3];
        Files.createDirectories(base);
        String token = HexFormat.of().formatHex(SecureRandom.getInstanceStrong().generateSeed(6));
        String realKey = "ACKREALDUMMY-" + token;
        String reply = "SSH-CONNECTOR-STUB-REPLY-" + token;
        Path clientKeys = base.resolve("client-keys");
        Path hostKeys1 = base.resolve("host-keys-1");
        Path hostKeys2 = base.resolve("host-keys-2");
        Path work = base.resolve("work");
        Files.createDirectories(work);
        String container1 = "ack-sshd-e2e-" + token + "-1";
        String container2 = "ack-sshd-e2e-" + token + "-2";
        int port = freePort();

        Stub stub = new Stub(reply);
        HttpServer upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/", stub::handle);
        upstream.start();
        try {
            ownerOnlyDir(clientKeys);
            run(List.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "ssh-connector-e2e-dummy",
                    "-f", clientKeys.resolve("id_ed25519").toString()));
            String hostPub1 = hostKeys(hostKeys1, clientKeys);
            startSshd(sshdImage, container1, hostKeys1, port);
            System.out.println("sshd " + container1 + " on 127.0.0.1:" + port);

            Map<String, String> settings = new LinkedHashMap<>();
            settings.put("ssh-connector.host.alias", "e2e-host");
            settings.put("ssh-connector.host.name", "127.0.0.1");
            settings.put("ssh-connector.host.port", String.valueOf(port));
            settings.put("ssh-connector.host.user", "dummy");
            settings.put("ssh-connector.key", clientKeys.resolve("id_ed25519").toString());
            settings.put("ssh-connector.work-dir", work.toString());
            settings.put("ssh-connector.limits.time-seconds", "30");
            settings.put("agent-connector-kernel.llm.upstream", "http://127.0.0.1:" + upstream.getAddress().getPort());

            // 2. Each tool once, directly.
            Path direct = base.resolve("connector-direct");
            Path localMarker = base.resolve("local-marker-" + token);
            try (ConnectorProcess connector = ConnectorProcess.start(jar, direct, settings, "Bearer " + realKey, List.of())) {
                connector.awaitServing(true);
                SocketMcpClient mcp = new SocketMcpClient(connector.socket(), "ssh-connector", connector.mcpKey());
                check("initialize", mcp.initialize().status() == 200, "initialize with the key answered 200");
                Path knownHosts = direct.resolve("private/ssh/known_hosts");
                check("known-hosts-starts-empty", Files.size(knownHosts) == 0, "known_hosts is empty before the first call");

                String hello = text(mcp.call("run_command", json("command", "echo hello-e2e && id -un")));
                check("run_command", hello.contains("exit 0") && hello.contains("hello-e2e") && hello.contains("dummy"),
                        "ran on the sshd container as dummy: " + oneLine(hello));
                String known = Files.readString(knownHosts);
                check("accept-new-recorded-the-host-key", known.contains("[127.0.0.1]:" + port) && known.contains(hostPub1),
                        "known_hosts now holds [127.0.0.1]:" + port + " with the container's host key");
                String option = text(mcp.call("run_command", json("command", "-oProxyCommand=touch " + localMarker)));
                check("option-text-runs-only-remotely", Files.notExists(localMarker) && option.startsWith("exit "),
                        "-oProxyCommand=touch <local file> reached the remote shell (" + oneLine(option)
                                + ") and no local file was made");

                String submitted = text(mcp.call("submit_job", new JSONObject().put("script", SCRIPT)
                        .put("name", "e2e-job").toString()));
                String gotScript = exec(container1, "cat", "/var/fake-slurm/sbatch.script");
                List<String> sbatchArgs = exec(container1, "cat", "/var/fake-slurm/sbatch.args").lines().toList();
                Matcher dir = Pattern.compile("job_dir: (\\S+)").matcher(submitted);
                String jobDir = dir.find() ? dir.group(1) : "";
                check("submit_job", submitted.contains("job_id: 4242") && SCRIPT.equals(gotScript)
                                && sbatchArgs.equals(List.of("--job-name=e2e-job", jobDir + "/job.sh"))
                                && jobDir.startsWith("ssh-connector/jobs/"),
                        "returned job_id 4242; the fake sbatch got the script byte for byte and the arguments " + sbatchArgs);

                String running = text(mcp.call("job_status", json("job_id", "4242")));
                check("job_status-squeue", running.contains("squeue") && running.contains("4242|e2e-job|RUNNING"),
                        oneLine(running));
                String ended = text(mcp.call("job_status", json("job_id", "77")));
                check("job_status-sacct", ended.contains("sacct") && ended.contains("77|e2e-job|COMPLETED"), oneLine(ended));
                String cancelled = text(mcp.call("cancel_job", json("job_id", "4242")));
                String scancelArgs = exec(container1, "cat", "/var/fake-slurm/scancel.args").strip();
                check("cancel_job", cancelled.startsWith("cancelled 4242") && scancelArgs.equals("4242"),
                        "the fake scancel got " + scancelArgs);

                String listed = text(mcp.call("list_remote", json("path", "ssh-connector/jobs")));
                String jobName = jobDir.substring(jobDir.lastIndexOf('/') + 1);
                check("list_remote", !jobName.isEmpty() && listed.contains(jobName), "ls -la lists " + jobName);

                String content = "uploaded by the E2E ' \" $(id) ü\n";
                Files.writeString(work.resolve("up.txt"), content);
                String up = text(mcp.call("upload_file", new JSONObject().put("local_path", "up.txt")
                        .put("remote_path", "uploaded e2e.txt").toString()));
                String remote = exec(container1, "cat", "/home/dummy/uploaded e2e.txt");
                check("upload_file", up.startsWith("uploaded") && content.equals(remote),
                        oneLine(up) + "; the remote file matches byte for byte");

                String down = text(mcp.call("download_file", new JSONObject().put("remote_path", "uploaded e2e.txt")
                        .put("local_path", "down/got.txt").toString()));
                boolean same = Files.exists(work.resolve("down/got.txt"))
                        && content.equals(Files.readString(work.resolve("down/got.txt")));
                String again = text(mcp.call("download_file", new JSONObject().put("remote_path", "uploaded e2e.txt")
                        .put("local_path", "down/got.txt").toString()));
                String escape = text(mcp.call("download_file", new JSONObject().put("remote_path", "uploaded e2e.txt")
                        .put("local_path", "../escaped.txt").toString()));
                check("download_file", down.startsWith("downloaded") && same && again.contains("overwrite")
                                && escape.startsWith("error:") && Files.notExists(base.resolve("escaped.txt")),
                        oneLine(down) + "; again without overwrite: " + oneLine(again) + "; ../escaped.txt: "
                                + oneLine(escape));

                // 3. A changed host key on the same address.
                stopSshd(container1);
                hostKeys(hostKeys2, clientKeys);
                startSshd(sshdImage, container2, hostKeys2, port);
                String knownBefore = Files.readString(knownHosts);
                String changed = text(mcp.call("run_command", json("command", "echo should-not-run")));
                check("host-key-change-refused", changed.startsWith("error:")
                                && changed.contains("Host key verification failed") && !changed.contains("should-not-run")
                                && knownBefore.equals(Files.readString(knownHosts)),
                        oneLine(changed) + "; known_hosts unchanged");
            }

            // 4. One confined harness: OpenCode with the stub model calls submit_job, then job_status.
            Map<String, String> harness = new LinkedHashMap<>(settings);
            harness.put("ssh-connector.harness.kind", "opencode");
            harness.put("ssh-connector.harness.image", harnessImage);
            harness.put("ssh-connector.harness.model", "dummy-model");
            Path harnessDir = base.resolve("connector-harness");
            try (ConnectorProcess connector = ConnectorProcess.start(jar, harnessDir, harness, "Bearer " + realKey,
                    List.of("run", PROMPT))) {
                boolean done = connector.process.waitFor(3, TimeUnit.MINUTES);
                if (!done) {
                    run(List.of("sh", "-c", "podman ps -q --filter ancestor=" + harnessImage + " | xargs -r podman stop -t 2"));
                }
                String output = Files.readString(connector.output);
                Files.writeString(base.resolve("harness-output.txt"), output);
                System.out.println("---- harness output (" + output.length() + " chars)");
                System.out.println(output.length() > 4000 ? output.substring(output.length() - 4000) : output);
                System.out.println("---- stub saw " + stub.seen);
                check("harness-exit-0", done && connector.process.exitValue() == 0,
                        "ssh-connector ended with the harness's status " + (done ? connector.process.exitValue() : "none"));
                List<String> answers = stub.seen.stream().map(s -> s.answered).filter(a -> !a.equals("model-list")).toList();
                int submitAt = indexOf(answers, "tool-call submit_job");
                int statusAt = indexOf(answers, "tool-call job_status");
                check("harness-called-submit_job-then-job_status", submitAt >= 0 && statusAt > submitAt
                        && answers.get(answers.size() - 1).equals("reply"), "the stub answered " + answers);
                String gotScript = exec(container2, "cat", "/var/fake-slurm/sbatch.script");
                check("harness-script-reached-sbatch", HARNESS_SCRIPT.equals(gotScript),
                        "the fake sbatch got the stub's script byte for byte");
                check("harness-got-the-tool-results", stub.seen.stream().anyMatch(s -> s.carriedJobId)
                                && stub.seen.stream().anyMatch(s -> s.carriedStatus),
                        "a later request carried job_id: 4242, and one carried the squeue or sacct answer");
                check("harness-printed-the-reply", output.contains(reply), "the stub's reply is in the harness's output");
                check("real-key-reached-the-stub", !stub.seen.isEmpty()
                                && stub.seen.stream().allMatch(s -> ("Bearer " + realKey).equals(s.authorization)),
                        "every request reached the stub through the proxy with the real (dummy) key");
                String left = run(List.of("podman", "ps", "-a", "-q", "--filter", "ancestor=" + harnessImage)).strip();
                check("no-harness-container-left", left.isEmpty(), "podman ps -a lists none for " + harnessImage);
                System.out.println("---- ssh-connector log\n" + connector.log());
            }
        } finally {
            upstream.stop(0);
            for (String c : List.of(container1, container2)) stopSshd(c);
            for (Path keys : List.of(clientKeys, hostKeys1, hostKeys2)) deleteTree(keys);
            check("dummy-keys-deleted", Files.notExists(clientKeys) && Files.notExists(hostKeys1)
                    && Files.notExists(hostKeys2), "the client key and both host keys are deleted");
        }
        int failures = 0;
        for (Map.Entry<String, String[]> c : checks.entrySet()) {
            if (!"PASS".equals(c.getValue()[0])) failures++;
            System.out.println("| " + c.getKey() + " | " + c.getValue()[0] + " | " + c.getValue()[1].replace("|", "/") + " |");
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** The stub model: submit_job first, then job_status with the id it got, then a reply. */
    static final class Stub {
        record Seen(String path, String authorization, boolean carriedJobId, boolean carriedStatus, String answered) {
            @Override
            public String toString() {
                return path + " jobId=" + carriedJobId + " status=" + carriedStatus + " -> " + answered;
            }
        }

        final List<Seen> seen = new CopyOnWriteArrayList<>();
        final String reply;
        int rounds;

        Stub(String reply) {
            this.reply = reply;
        }

        void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String path = exchange.getRequestURI().getPath();
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (path.equals("/v1/models")) {
                    send(exchange, "application/json", new JSONObject().put("object", "list").put("data", new JSONArray()
                            .put(new JSONObject().put("id", "dummy-model").put("object", "model"))).toString());
                    seen.add(new Seen(path, auth, false, false, "model-list"));
                    return;
                }
                if (!path.equals("/v1/chat/completions")) {
                    exchange.sendResponseHeaders(404, -1);
                    seen.add(new Seen(path, auth, false, false, "404"));
                    return;
                }
                JSONObject request = new JSONObject(body);
                boolean jobId = body.contains("job_id: 4242");
                boolean status = body.contains("squeue (JOBID") || body.contains("sacct (JobID");
                String tool = null;
                String arguments = null;
                // A request without tools (OpenCode's title request) gets the reply and is not a round.
                JSONArray offered = request.optJSONArray("tools");
                if (offered != null && !offered.isEmpty() && ++rounds <= 4) {
                    if (!jobId) {
                        tool = toolNamed(request.optJSONArray("tools"), "submit_job");
                        arguments = new JSONObject().put("script", HARNESS_SCRIPT).put("name", "harness-job").toString();
                    } else if (!status) {
                        tool = toolNamed(request.optJSONArray("tools"), "job_status");
                        arguments = new JSONObject().put("job_id", "4242").toString();
                    }
                }
                String answered = tool == null ? "reply"
                        : tool.endsWith("submit_job") ? "tool-call submit_job" : "tool-call job_status";
                seen.add(new Seen(path, auth, jobId, status, answered));
                JSONObject delta = new JSONObject().put("role", "assistant");
                if (tool != null) {
                    delta.put("tool_calls", new JSONArray().put(new JSONObject().put("index", 0)
                            .put("id", "call_" + rounds).put("type", "function")
                            .put("function", new JSONObject().put("name", tool).put("arguments", arguments))));
                } else {
                    delta.put("content", reply);
                }
                String finish = tool != null ? "tool_calls" : "stop";
                StringBuilder sse = new StringBuilder();
                sse.append("data: ").append(chunk(delta, null)).append("\n\n");
                sse.append("data: ").append(chunk(new JSONObject(), finish)).append("\n\n");
                sse.append("data: ").append(new JSONObject().put("id", "chatcmpl-e2e").put("object", "chat.completion.chunk")
                        .put("created", 0).put("model", "dummy-model").put("choices", new JSONArray())
                        .put("usage", new JSONObject().put("prompt_tokens", 10).put("completion_tokens", 5)
                                .put("total_tokens", 15))).append("\n\n");
                sse.append("data: [DONE]\n\n");
                send(exchange, "text/event-stream", sse.toString());
            }
        }

        static String toolNamed(JSONArray tools, String suffix) {
            if (tools == null) return null;
            for (int i = 0; i < tools.length(); i++) {
                JSONObject f = tools.getJSONObject(i).optJSONObject("function");
                String name = f == null ? null : f.optString("name", null);
                if (name != null && (name.equals(suffix) || name.endsWith("_" + suffix))) return name;
            }
            return null;
        }

        static JSONObject chunk(JSONObject delta, String finish) {
            return new JSONObject().put("id", "chatcmpl-e2e").put("object", "chat.completion.chunk").put("created", 0)
                    .put("model", "dummy-model").put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                            .put("delta", delta).put("finish_reason", finish == null ? JSONObject.NULL : finish)));
        }

        static void send(HttpExchange exchange, String type, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream o = exchange.getResponseBody()) {
                o.write(bytes);
            }
        }
    }

    static int indexOf(List<String> list, String value) {
        return list.indexOf(value);
    }

    /** Makes a fresh host key in {@code dir} and authorized_keys holding the client's public key; returns the host key's base64. */
    static String hostKeys(Path dir, Path clientKeys) throws Exception {
        ownerOnlyDir(dir);
        run(List.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "ssh-connector-e2e-host", "-f",
                dir.resolve("host_key").toString()));
        Path authorized = dir.resolve("authorized_keys");
        Files.copy(clientKeys.resolve("id_ed25519.pub"), authorized);
        Files.setPosixFilePermissions(authorized, PosixFilePermissions.fromString("rw-------"));
        return Files.readString(dir.resolve("host_key.pub")).split(" ")[1];
    }

    static void startSshd(String image, String name, Path keys, int port) throws Exception {
        run(List.of("nice", "-n", "19", "podman", "run", "-d", "--rm", "--name", name, "-p", "127.0.0.1:" + port + ":2222",
                "-v", keys + ":/keys:ro", image));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
                s.setSoTimeout(3000);
                byte[] b = new byte[8];
                int n = s.getInputStream().read(b);
                if (n > 0 && new String(b, 0, n, StandardCharsets.US_ASCII).startsWith("SSH-2.0")) return;
            } catch (IOException e) {
                // not up yet
            }
            Thread.sleep(300);
        }
        throw new IOException("sshd " + name + " did not answer on 127.0.0.1:" + port + "\n"
                + run(List.of("podman", "logs", name)));
    }

    static void stopSshd(String name) throws Exception {
        run(List.of("podman", "stop", "--ignore", "-t", "2", name));
    }

    /** @return the standard output of a command in the container, exactly */
    static String exec(String container, String... command) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("podman", "exec", container));
        cmd.addAll(Arrays.asList(command));
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor(30, TimeUnit.SECONDS);
        return new String(out, StandardCharsets.UTF_8);
    }

    static String run(List<String> command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(out);
        }
        if (!p.waitFor(5, TimeUnit.MINUTES)) p.destroyForcibly();
        String text = out.toString(StandardCharsets.UTF_8);
        if (p.exitValue() != 0) throw new IOException(String.join(" ", command) + " exited " + p.exitValue() + ":\n" + text);
        return text;
    }

    /** @return the text of a tools/call answer: its first content item, or the whole JSON-RPC message */
    static String text(SocketMcpClient.Answer answer) {
        try {
            JSONObject message = new JSONObject(answer.json());
            JSONObject result = message.optJSONObject("result");
            if (result == null) return "error: " + message;
            JSONArray content = result.optJSONArray("content");
            return content == null || content.isEmpty() ? result.toString() : content.getJSONObject(0).optString("text");
        } catch (Exception e) {
            return "error: unreadable answer " + answer;
        }
    }

    static String json(String key, String value) {
        return new JSONObject().put(key, value).toString();
    }

    static String oneLine(String s) {
        String t = s.replace("\n", "\\n");
        return t.length() > 240 ? t.substring(0, 240) + "..." : t;
    }

    static void check(String name, boolean ok, String detail) {
        checks.put(name, new String[] {ok ? "PASS" : "FAIL", detail});
    }

    static void ownerOnlyDir(Path dir) throws IOException {
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (Files.notExists(dir)) return;
        try (var files = Files.walk(dir)) {
            for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
