import com.scivicslab.chatui.confine.CodexHarness;
import com.scivicslab.chatui.confine.HermesHarness;
import com.scivicslab.chatui.confine.OpenCodeHarness;
import com.scivicslab.chatui.confine.PodmanConfinement;
import com.scivicslab.chatui.confine.PodmanHarness;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 9 part 3 (tasks/todo.md): the real harness, confined exactly as agent-connector-kernel's launcher confines it,
 * against a stub model. An E2E program outside the Maven build (TestingStandard_260404_oo01), run on a host with
 * rootless Podman.
 *
 * <pre>
 * java -Dack.host.jar=&lt;host jar&gt; -cp &lt;agent-connector-kernel jar and org.json&gt; e2e/HarnessStubRun.java &lt;codex|opencode|hermes&gt; &lt;image&gt; &lt;run dir&gt; [&lt;traced image&gt;]
 * </pre>
 *
 * On the host it starts agent-connector-kernel's MCP server and LLM proxy on Unix sockets only, and behind the proxy
 * a stub OpenAI-compatible upstream on host 127.0.0.1 that answers both the Chat Completions and the Responses API.
 * The stub's first answer to a request offering the MCP tool {@code calc} is a call to it; once the tool's result is
 * in the request, it answers with one canned reply. The container is started with {@link PodmanConfinement#arguments},
 * whose trailing harness command gets the harness's own one-shot arguments and a prompt. Every key is a dummy.
 *
 * <p>With a traced image (the harness image plus {@code strace}), it runs the same harness a second time under
 * {@code strace -f -e trace=%network} and lists every address the harness's processes tried to reach.</p>
 */
public class HarnessStubRun {

    static final String PROMPT = "Use the calc tool to compute 6*7, then answer.";
    /** Set with --real: the model server the host-side upstream forwards to, and the model name it serves. */
    static URI REAL_UPSTREAM;
    static String REAL_MODEL;
    static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();
    static final String EXPRESSION = "6*7";
    /** The stub's cap on tool-call answers per run. */
    static final int MAX_TOOL_ROUNDS = 3;
    static final java.util.concurrent.atomic.AtomicInteger TOOL_ROUNDS = new java.util.concurrent.atomic.AtomicInteger();
    /** The test host's uber-jar (e2e/confinement-host), from -Dack.host.jar. */
    static final Path HOST_JAR = Path.of(System.getProperty("ack.host.jar", "ack-confinement-host.jar")).toAbsolutePath();

    /** One request the stub upstream received. */
    record Seen(String method, String path, String authorization, boolean carriedToolResult, String answeredWith) {
    }

    public static void main(String[] args) throws Exception {
        String which = args[0];
        String image = args[1];
        Path base = Path.of(args[2]).toAbsolutePath();
        boolean real = args.length > 3 && args[3].equals("--real");
        String tracedImage = !real && args.length > 3 ? args[3] : null;
        if (real) {
            // A real model: the host-side upstream forwards to it (one request at a time) instead of answering.
            REAL_UPSTREAM = URI.create(args[4]);
            REAL_MODEL = args[5];
        }
        PodmanHarness harness = switch (which) {
            case "codex" -> new CodexHarness();
            case "opencode" -> new OpenCodeHarness();
            case "hermes" -> new HermesHarness();
            default -> throw new IllegalArgumentException("codex, opencode or hermes");
        };
        int failures = run(which, harness, image, base.resolve("plain"), false);
        if (tracedImage != null) failures += run(which, harness, tracedImage, base.resolve("traced"), true);
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** The harness's own arguments for one prompt with no terminal UI, after its command. */
    static List<String> oneShot(String which) {
        return switch (which) {
            // https://learn.chatgpt.com/codex/noninteractive : codex exec; the working directory is not a git repository
            case "codex" -> List.of("exec", "--skip-git-repo-check", PROMPT);
            // https://opencode.ai/v1/docs/cli : opencode run [message]
            case "opencode" -> List.of("run", PROMPT);
            // https://hermes-agent.nousresearch.com/docs/reference/cli-commands : hermes chat -q, -Q programmatic mode
            case "hermes" -> List.of("chat", "-q", PROMPT, "-Q");
            default -> throw new IllegalArgumentException(which);
        };
    }

    static int run(String which, PodmanHarness harness, String image, Path base, boolean traced) throws Exception {
        TOOL_ROUNDS.set(0);
        String token = HexFormat.of().formatHex(SecureRandom.getInstanceStrong().generateSeed(8));
        String realKey = "ACKREALDUMMY-" + token;
        String reply = "ACK-STUB-REPLY-" + token;
        String toolResult = "ACK-TOOL-RESULT-" + token;
        Path kernelDir = base.resolve("kernel-dir");
        Files.createDirectories(kernelDir);
        Path workDir = base.resolve("work");
        Files.createDirectories(workDir);
        Path configFile = base.resolve("config-" + token);
        Path bodies = base.resolve("upstream-bodies");
        Files.createDirectories(bodies);

        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/", exchange -> {
            try (exchange) {
                byte[] in = exchange.getRequestBody().readAllBytes();
                String body = new String(in, StandardCharsets.UTF_8);
                String path = exchange.getRequestURI().getPath();
                Files.writeString(bodies.resolve(String.format("%02d", seen.size()) + "-"
                        + path.replaceAll("[^A-Za-z0-9]+", "_") + ".txt"), exchange.getRequestMethod() + " " + path + "\n" + body);
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                boolean carried = body.contains(toolResult);
                String answered = REAL_UPSTREAM != null ? forward(exchange, body)
                        : answer(exchange, path, body, carried, reply);
                seen.add(new Seen(exchange.getRequestMethod(), path, auth, carried, answered));
            }
        });
        upstream.start();

        Map<String, String[]> checks = new LinkedHashMap<>();
        String label = which + (REAL_MODEL != null ? " + " + REAL_MODEL : "") + (traced ? " (traced)" : "");
        // agent-connector-kernel's MCP server and LLM proxy in a Quarkus application on one domain socket (step 5). calc
        // answers "42 (<marker>)", so a real model gets the right answer and the marker shows the result came back.
        try (ConfinementHost host = ConfinementHost.start(HOST_JAR, base, "stub", workDir,
                URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), "Bearer " + realKey, toolResult)) {
            Path socketDir = host.socketDir;
            String mcpKey = host.mcpKey;
            String llmKey = host.llmKey;
            System.out.println("host socket " + host.socket + " " + host.permissions());
            String configText = harness.configText("stub", REAL_MODEL != null ? REAL_MODEL : "dummy-model");
            String prefix = System.getenv().getOrDefault("HSR_DIAG_CONFIG_PREFIX", "").replace("\\n", "\n");
            String suffix = System.getenv().getOrDefault("HSR_DIAG_CONFIG_SUFFIX", "").replace("\\n", "\n");
            String jsonPut = System.getenv().getOrDefault("HSR_DIAG_JSON_PUT", "");
            if (!jsonPut.isEmpty()) {
                JSONObject merged = new JSONObject(configText);
                JSONObject put = new JSONObject(jsonPut);
                for (String k : put.keySet()) merged.put(k, put.get(k));
                configText = merged.toString(2) + "\n";
                System.out.println("DIAGNOSTIC RUN: config is\n" + configText);
            }
            if (!prefix.isEmpty() || !suffix.isEmpty()) {
                // Diagnosis only, never in a reported run: trying a setting before it goes into the launcher.
                configText = prefix + configText + suffix;
                System.out.println("DIAGNOSTIC RUN: config is\n" + configText);
            }
            Files.writeString(configFile, configText);

            PodmanConfinement.Launch launch = new PodmanConfinement.Launch(image, kernelDir, socketDir, workDir, configFile,
                    harness, mcpKey, llmKey);
            List<String> launcherArgs = PodmanConfinement.arguments(launch);
            List<String> command = new ArrayList<>(List.of("nice", "-n", "19"));
            command.addAll(launcherArgs.subList(0, launcherArgs.size() - harness.command().size()));
            String diagEnv = System.getenv("HSR_DIAG_ENV");
            if (diagEnv != null && !diagEnv.isBlank()) {
                // Diagnosis only, never in a reported run: extra variables for the harness's own logging.
                int imageAt = command.lastIndexOf(image);
                for (String pair : diagEnv.split(";")) command.addAll(imageAt, List.of("-e", pair));
                System.out.println("DIAGNOSTIC RUN: extra -e " + diagEnv);
            }
            List<String> harnessCommand = new ArrayList<>(harness.command());
            harnessCommand.addAll(oneShot(which));
            if (traced) {
                // strace must not become PID 1: the relay's two socat processes are PID 1's children, and strace as
                // PID 1 waits for them. And the harness may leave a server of its own running after its main process
                // ends (Hermes does), which strace -f would also wait for. So sh stays PID 1, the harness runs under
                // strace with its exit status written to a file, and sh stops strace once that file appears.
                String trace = System.getenv().getOrDefault("HSR_DIAG_TRACE", "%network");
                command.addAll(List.of("sh", "-c", String.join("\n",
                        "strace -f -qq -s 200 -e trace=" + trace + " -o " + PodmanConfinement.WORK_DIR
                                + "/strace.log sh -c '\"$@\"; echo $? > /tmp/ack-harness.exit' sh \"$@\" &",
                        "S=$!",
                        "while [ ! -e /tmp/ack-harness.exit ] && kill -0 $S 2>/dev/null; do sleep 0.2; done",
                        // strace does not always end on SIGTERM while a tracee is running; then SIGKILL after 5 s.
                        "kill $S 2>/dev/null; for i in 1 2 3 4 5 6 7 8 9 10; do kill -0 $S 2>/dev/null || break; sleep 0.5; done",
                        "kill -9 $S 2>/dev/null; wait $S 2>/dev/null",
                        "exit $(cat /tmp/ack-harness.exit 2>/dev/null || echo 125)"), "sh"));
            }
            command.addAll(harnessCommand);
            System.out.println("== " + label);
            System.out.println("launcher arguments: " + String.join(" ", launcherArgs));
            System.out.println("run: " + String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            pb.environment().putAll(PodmanConfinement.environment(launch));
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    p.getInputStream().transferTo(out);
                } catch (IOException ignored) {
                }
            });
            // About 2 minutes for a stub run (chat-48, t161:r1); a real model may take longer.
            // Traced runs get 8 minutes: Hermes starts slowly under strace (chat-48, t162:r1).
            int limitMinutes = REAL_UPSTREAM != null || traced ? 8 : 2;
            boolean ended = p.waitFor(limitMinutes, TimeUnit.MINUTES);
            if (!ended) {
                // Killing the podman client does not stop the container; stop it by the image it runs.
                new ProcessBuilder("sh", "-c", "podman ps -q --filter ancestor=" + image + " | xargs -r podman stop -t 2")
                        .inheritIO().start().waitFor();
                p.destroyForcibly().waitFor();
            }
            reader.join(5000);
            List<String> calcCalls = host.calcCalls();
            String output = out.toString(StandardCharsets.UTF_8);
            Files.writeString(base.resolve("harness-output.txt"), output);
            System.out.println("---- harness output (" + output.length() + " chars; exit "
                    + (ended ? p.exitValue() : "killed after " + limitMinutes + " minutes") + ")");
            System.out.println(output.length() > 6000 ? output.substring(0, 3000) + "\n[...]\n" + output.substring(output.length() - 3000) : output);
            System.out.println("---- stub upstream saw");
            seen.forEach(s -> System.out.println("  " + s.method() + " " + s.path() + " auth="
                    + (("Bearer " + realKey).equals(s.authorization()) ? "<real dummy key>" : String.valueOf(s.authorization()))
                    + " toolResultInRequest=" + s.carriedToolResult() + " answered=" + s.answeredWith()));
            System.out.println("---- calc calls on the MCP server: " + calcCalls);

            // The container's only inputs: podman's arguments, its environment, and the mounts (sockets, work dir,
            // config file). None may carry the real key; part 2 showed the same from inside the container.
            boolean inArgs = String.join(" ", command).contains(realKey);
            boolean inEnv = PodmanConfinement.environment(launch).values().stream().anyMatch(v -> v.contains(realKey));
            boolean inMounts;
            try (var files = Files.walk(workDir)) {
                inMounts = Files.readString(configFile).contains(realKey) || files.filter(Files::isRegularFile)
                        .anyMatch(f -> { try { return Files.readString(f).contains(realKey); } catch (IOException e) { return false; } });
            }
            checks.put("real-key-not-given-to-the-container", !inArgs && !inEnv && !inMounts
                    ? pass("not in podman's arguments, not in the -e variables, not in the config or any file under /work")
                    : fail("in arguments " + inArgs + ", in -e variables " + inEnv + ", in mounted files " + inMounts));
            checks.put("exits-0", ended && p.exitValue() == 0
                    ? pass("the harness ended with exit 0") : fail("exit " + (ended ? p.exitValue() : "none, killed")));
            boolean modelCall = seen.stream().anyMatch(s -> s.path().matches("/v1/(chat/completions|responses)"));
            boolean allReal = !seen.isEmpty() && seen.stream().allMatch(s -> ("Bearer " + realKey).equals(s.authorization()));
            checks.put("request-reached-stub-with-real-key", modelCall && allReal
                    ? pass("every request the stub got carried Authorization: Bearer <real dummy key>")
                    : fail("model call " + modelCall + ", all with the real key " + allReal));
            boolean toolCalled = REAL_UPSTREAM != null ? !calcCalls.isEmpty()
                    : seen.stream().anyMatch(s -> s.answeredWith().startsWith("tool-call"));
            checks.put("tool-call-reached-mcp-and-returned", toolCalled
                    && (REAL_UPSTREAM != null || calcCalls.contains(EXPRESSION))
                    && seen.stream().anyMatch(Seen::carriedToolResult)
                    ? pass("stub answered with a calc call; the MCP server ran calc(" + EXPRESSION
                    + "); the next request carried its result")
                    : fail("stub tool call " + toolCalled + ", MCP calc calls " + calcCalls + ", result returned "
                    + seen.stream().anyMatch(Seen::carriedToolResult)));
            if (REAL_UPSTREAM != null) {
                String first = firstReplyLine(which, output);
                boolean modelAnswered = seen.stream().anyMatch(s -> s.path().matches("/v1/(chat/completions|responses)")
                        && s.answeredWith().startsWith("forwarded 200"));
                checks.put("real-reply-printed", modelAnswered && first != null
                        ? pass("the model answered with 200 and the harness printed: " + first)
                        : fail("model answered 200: " + modelAnswered + "; first reply line: " + first));
            } else {
                checks.put("canned-reply-printed", output.contains(reply)
                        ? pass("the harness printed the stub's canned reply") : fail("the canned reply is not in the output"));
            }
            if (traced) {
                checks.put("no-network-beyond-the-two-sockets", networkVerdict(workDir.resolve("strace.log")));
            }
        } finally {
            upstream.stop(0);
            Files.deleteIfExists(configFile);
        }
        int failures = 0;
        for (Map.Entry<String, String[]> c : checks.entrySet()) {
            if (!"PASS".equals(c.getValue()[0])) failures++;
            System.out.println("| " + label + " | " + c.getKey() + " | " + c.getValue()[0] + " | "
                    + c.getValue()[1].replace("|", "/") + " |");
        }
        return failures;
    }

    /**
     * Forwards one request to the real model server and streams its answer back, as received. The JDK server's default
     * executor handles one exchange at a time, so the model server gets one request at a time.
     */
    static String forward(HttpExchange exchange, String body) throws IOException {
        String target = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
        HttpRequest.Builder out = HttpRequest.newBuilder(REAL_UPSTREAM.resolve(target))
                .timeout(java.time.Duration.ofMinutes(6))
                .method(exchange.getRequestMethod(), body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type != null) out.header("Content-Type", type);
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        if (accept != null) out.header("Accept", accept);
        HttpResponse<java.io.InputStream> response;
        try {
            response = CLIENT.send(out.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException | IOException e) {
            send(exchange, 502, "application/json", "{\"error\":\"forward failed: " + e + "\"}");
            return "forward failed: " + e;
        }
        response.headers().firstValue("content-type").ifPresent(v -> exchange.getResponseHeaders().set("Content-Type", v));
        exchange.sendResponseHeaders(response.statusCode(), 0);
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        try (java.io.InputStream in = response.body(); OutputStream o = exchange.getResponseBody()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                o.write(buffer, 0, n);
                o.flush();
                if (head.size() < 600) head.write(buffer, 0, Math.min(n, 600 - head.size()));
            }
        }
        String start = head.toString(StandardCharsets.UTF_8);
        Matcher served = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"").matcher(start);
        return "forwarded " + response.statusCode() + (served.find() ? " model=" + served.group(1) : "")
                + (response.statusCode() >= 400 ? " " + start.replaceAll("\\s+", " ") : "");
    }

    /** @return the first non-empty line of the harness's answer, skipping its banner and notices */
    static String firstReplyLine(String which, String output) {
        List<String> lines = output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        int from = 0;
        if (which.equals("codex")) {
            // codex exec prints its header, the prompt, then "codex" before the answer
            for (int i = lines.size() - 1; i >= 0; i--) if (lines.get(i).equals("codex")) { from = i + 1; break; }
        }
        for (int i = from; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("[hermes] WARNING") || l.startsWith("warning:") || l.startsWith("session_id:")
                    || l.startsWith("> build") || l.matches("\\W*\\[0m\\W*") || l.equals("tokens used")) continue;
            if (l.startsWith("\u001b")) l = l.replaceAll("\u001b\\[[0-9;]*m", "").strip();
            if (l.isEmpty() || l.startsWith("> build") || l.startsWith("⚙") || l.startsWith("|")) continue;
            return l.length() > 200 ? l.substring(0, 200) + "…" : l;
        }
        return null;
    }

    static String[] pass(String why) {
        return new String[] {"PASS", why};
    }

    static String[] fail(String why) {
        return new String[] {"FAIL", why};
    }

    /** Answers one request to the stub upstream; returns what it answered with. */
    static String answer(HttpExchange exchange, String path, String body, boolean carriedToolResult, String reply)
            throws IOException {
        if (path.equals("/v1/models")) {
            send(exchange, 200, "application/json", new JSONObject().put("object", "list").put("data", new JSONArray()
                    .put(new JSONObject().put("id", "dummy-model").put("object", "model").put("owned_by", "stub"))).toString());
            return "model-list";
        }
        boolean responses = path.equals("/v1/responses");
        if (!responses && !path.equals("/v1/chat/completions")) {
            send(exchange, 404, "application/json", "{\"error\":{\"message\":\"stub: no such path\"}}");
            return "404";
        }
        JSONObject request = new JSONObject(body);
        String tool = carriedToolResult ? null : calcToolName(request.optJSONArray("tools"), responses);
        // A hard cap: after MAX_TOOL_ROUNDS calls the stub answers with its reply, so a harness that refuses the tool
        // ends the run instead of looping (chat-48, t161:r1).
        if (tool != null && TOOL_ROUNDS.incrementAndGet() > MAX_TOOL_ROUNDS) tool = null;
        boolean stream = request.optBoolean("stream", false);
        String arguments = new JSONObject().put("expression", EXPRESSION).toString();
        String answered = tool != null ? "tool-call " + tool : "reply";
        if (responses) {
            JSONObject item = tool != null
                    ? functionCall(tool, arguments)
                    : new JSONObject().put("type", "message").put("id", "msg_1").put("role", "assistant")
                      .put("status", "completed").put("content", new JSONArray().put(new JSONObject()
                              .put("type", "output_text").put("text", reply).put("annotations", new JSONArray())));
            JSONObject usage = new JSONObject().put("input_tokens", 10).put("output_tokens", 5).put("total_tokens", 15)
                    .put("input_tokens_details", new JSONObject().put("cached_tokens", 0))
                    .put("output_tokens_details", new JSONObject().put("reasoning_tokens", 0));
            JSONObject done = new JSONObject().put("id", "resp_ack").put("object", "response").put("status", "completed")
                    .put("model", "dummy-model").put("output", new JSONArray().put(item)).put("usage", usage);
            StringBuilder sse = new StringBuilder();
            event(sse, "response.created", new JSONObject().put("type", "response.created").put("response",
                    new JSONObject().put("id", "resp_ack").put("object", "response").put("status", "in_progress")
                            .put("model", "dummy-model").put("output", new JSONArray())));
            JSONObject added = new JSONObject(item.toString());
            if (tool == null) added.put("content", new JSONArray()).put("status", "in_progress");
            event(sse, "response.output_item.added", new JSONObject().put("type", "response.output_item.added")
                    .put("output_index", 0).put("item", added));
            if (tool == null) {
                event(sse, "response.output_text.delta", new JSONObject().put("type", "response.output_text.delta")
                        .put("item_id", "msg_1").put("output_index", 0).put("content_index", 0).put("delta", reply));
            }
            event(sse, "response.output_item.done", new JSONObject().put("type", "response.output_item.done")
                    .put("output_index", 0).put("item", item));
            event(sse, "response.completed", new JSONObject().put("type", "response.completed").put("response", done));
            if (stream) send(exchange, 200, "text/event-stream", sse.toString());
            else send(exchange, 200, "application/json", done.toString());
            return answered;
        }
        JSONObject message = new JSONObject().put("role", "assistant");
        if (tool != null) {
            message.put("content", JSONObject.NULL).put("tool_calls", new JSONArray().put(new JSONObject()
                    .put("id", "call_ack_1").put("type", "function")
                    .put("function", new JSONObject().put("name", tool).put("arguments", arguments))));
        } else {
            message.put("content", reply);
        }
        String finish = tool != null ? "tool_calls" : "stop";
        JSONObject usage = new JSONObject().put("prompt_tokens", 10).put("completion_tokens", 5).put("total_tokens", 15);
        if (!stream) {
            send(exchange, 200, "application/json", new JSONObject().put("id", "chatcmpl-ack").put("object", "chat.completion")
                    .put("created", 0).put("model", "dummy-model").put("choices", new JSONArray().put(new JSONObject()
                            .put("index", 0).put("message", message).put("finish_reason", finish))).put("usage", usage).toString());
            return answered;
        }
        JSONObject delta = new JSONObject(message.toString());
        if (tool != null) {
            delta.remove("content");
            delta.getJSONArray("tool_calls").getJSONObject(0).put("index", 0);
        }
        StringBuilder sse = new StringBuilder();
        sse.append("data: ").append(chunk(delta, null)).append("\n\n");
        sse.append("data: ").append(chunk(new JSONObject(), finish)).append("\n\n");
        sse.append("data: ").append(new JSONObject().put("id", "chatcmpl-ack").put("object", "chat.completion.chunk")
                .put("created", 0).put("model", "dummy-model").put("choices", new JSONArray()).put("usage", usage)).append("\n\n");
        sse.append("data: [DONE]\n\n");
        send(exchange, 200, "text/event-stream", sse.toString());
        return answered;
    }

    /** A Responses API function_call item; a {@code <namespace>/<name>} tool is called with its namespace. */
    static JSONObject functionCall(String tool, String arguments) {
        JSONObject call = new JSONObject().put("type", "function_call").put("id", "fc_1").put("call_id", "call_ack_1")
                .put("arguments", arguments).put("status", "completed");
        int slash = tool.indexOf('/');
        if (slash < 0) return call.put("name", tool);
        return call.put("name", tool.substring(slash + 1)).put("namespace", tool.substring(0, slash));
    }

    static JSONObject chunk(JSONObject delta, String finish) {
        return new JSONObject().put("id", "chatcmpl-ack").put("object", "chat.completion.chunk").put("created", 0)
                .put("model", "dummy-model").put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                        .put("delta", delta).put("finish_reason", finish == null ? JSONObject.NULL : finish)));
    }

    static void event(StringBuilder sse, String name, JSONObject data) {
        sse.append("event: ").append(name).append("\ndata: ").append(data).append("\n\n");
    }

    /**
     * @return the name under which the harness offers the MCP server's {@code calc}, or {@code null}. Codex groups its
     *         MCP tools in a Responses API {@code namespace} entry; such a tool is returned as {@code <namespace>/calc}
     *         and called with {@code name: calc, namespace: <namespace>} (codex-rs protocol/src/models.rs,
     *         {@code FunctionCall.namespace}, at rust-v0.171.0).
     */
    static String calcToolName(JSONArray tools, boolean responses) {
        if (tools == null) return null;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.getJSONObject(i);
            if (responses && "namespace".equals(t.optString("type")) && t.optJSONArray("tools") != null) {
                JSONArray inner = t.getJSONArray("tools");
                for (int j = 0; j < inner.length(); j++) {
                    if ("calc".equals(inner.getJSONObject(j).optString("name"))) return t.getString("name") + "/calc";
                }
                continue;
            }
            String name = responses ? t.optString("name", null) : t.optJSONObject("function") == null ? null
                    : t.getJSONObject("function").optString("name", null);
            if (name != null && (name.equals("calc") || name.matches(".*[^A-Za-z0-9]calc"))) return name;
        }
        return null;
    }

    static String portOf(String target) {
        Matcher m = Pattern.compile("sin_port=htons\\((\\d+)\\)").matcher(target);
        return m.find() ? m.group(1) : null;
    }

    static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream o = exchange.getResponseBody()) {
            o.write(bytes);
        }
    }

    /**
     * Reads the strace log: every address a process of the harness tried to connect or send to. The one socket and
     * the relay's two ports are allowed; netlink (the kernel's own interface queries) is listed but is not network.
     */
    static String[] networkVerdict(Path log) throws IOException {
        if (!Files.exists(log)) return fail("no strace log at " + log);
        Pattern call = Pattern.compile("\\b(connect|sendto|sendmsg|sendmmsg)\\(\\d+, .*?(sa_family=AF_[A-Z0-9]+[^}]*)\\}");
        TreeSet<String> allowed = new TreeSet<>();
        TreeSet<String> other = new TreeSet<>();
        TreeSet<String> netlink = new TreeSet<>();
        TreeSet<String> localUnix = new TreeSet<>();
        // Ports a traced process itself listens on, on the container's own loopback (with --network=none the
        // container's 127.0.0.1 is its own; part 2 showed the host's loopback cannot be reached).
        Pattern own = Pattern.compile("\\b(?:bind|getsockname)\\((\\d+), \\{sa_family=AF_INET, sin_port=htons\\((\\d+)\\), sin_addr=inet_addr\\(\"127\\.0\\.0\\.1\"\\)");
        Pattern listen = Pattern.compile("\\blisten\\((\\d+),");
        List<String> lines = Files.readAllLines(log);
        java.util.Set<String> listeningFds = new java.util.HashSet<>();
        for (String line : lines) {
            Matcher l = listen.matcher(line);
            if (l.find()) listeningFds.add(l.group(1));
        }
        java.util.Set<String> ownPorts = new java.util.HashSet<>();
        for (String line : lines) {
            Matcher o = own.matcher(line);
            if (o.find() && !o.group(2).equals("0") && listeningFds.contains(o.group(1))) ownPorts.add(o.group(2));
        }
        System.out.println("---- strace: ports a traced process listens on, on the container's loopback " + ownPorts);
        TreeSet<String> inContainer = new TreeSet<>();
        for (String line : lines) {
            Matcher m = call.matcher(line);
            if (!m.find()) continue;
            String target = m.group(2).replaceAll("\\s+", " ");
            if (target.startsWith("sa_family=AF_NETLINK")) {
                netlink.add(m.group(1) + " " + target);
            } else if (target.startsWith("sa_family=AF_UNIX") && !target.contains("/run/agent-connector-kernel/")) {
                // A Unix socket on the container's own filesystem (e.g. glibc's absent nscd socket for a passwd
                // lookup): only the one socket is mounted in, so this reaches nothing outside the container.
                localUnix.add(m.group(1) + " " + target);
            } else if (target.startsWith("sa_family=AF_INET") && target.contains("inet_addr(\"127.0.0.1\")")
                    && portOf(target) != null && !portOf(target).equals("18765") && !portOf(target).equals("18766")
                    && ownPorts.contains(portOf(target))) {
                inContainer.add(m.group(1) + " " + target);
            } else if (target.contains("\"/run/agent-connector-kernel/agent-connector-kernel.sock\"")
                    || (target.contains("sin_port=htons(18765)") || target.contains("sin_port=htons(18766)"))
                    && target.contains("127.0.0.1")) {
                allowed.add(target);
            } else {
                other.add(m.group(1) + " " + target);
            }
        }
        System.out.println("---- strace: allowed targets " + allowed);
        System.out.println("---- strace: netlink " + netlink);
        System.out.println("---- strace: local Unix sockets (not network) " + localUnix);
        System.out.println("---- strace: the harness's own server on the container's loopback (not network) " + inContainer);
        System.out.println("---- strace: other targets " + other);
        if (allowed.isEmpty()) return fail("the trace shows no connection even to the socket; it did not see the harness");
        return other.isEmpty()
                ? pass("strace -f -e trace=%network: every connect/send went to 127.0.0.1:18765/18766 (the relay to the one socket) or the socket")
                : fail("other targets: " + other);
    }
}
