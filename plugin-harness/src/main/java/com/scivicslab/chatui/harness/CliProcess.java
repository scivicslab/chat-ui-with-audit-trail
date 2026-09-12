package com.scivicslab.chatui.harness;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Manages a CLI LLM subprocess (claude, codex, etc.) using stream-json I/O.
 *
 * <p>The binary name and API key environment variable are supplied at construction
 * time, making this class usable by both Claude and Codex providers without
 * duplication.</p>
 *
 * <p>With {@code --input-format stream-json}, the process stays alive across turns.
 * {@link #sendPrompt} writes to stdin and reads stdout until a result event arrives.</p>
 */
public class CliProcess {

    private static final Logger logger = Logger.getLogger(CliProcess.class.getName());

    private static final Pattern ANSI_PATTERN = Pattern.compile(
        "\\x1b\\[[0-9;?]*[a-zA-Z]|\\x1b\\][^\u0007]*\u0007|\\x1b[^\\[\\]].?"
    );

    private final String binary;
    private final String apiKeyEnvVar;
    private final StreamEventParser parser = new StreamEventParser();

    private CliConfig config;
    private String lastSessionId;
    private Process currentProcess;
    private OutputStream stdinStream;
    private volatile Thread readerThread;
    private volatile Thread sendingThread;
    // Events produced while a prompt turn is in flight (between writeUserMessage and the turn's
    // result event) go to turnQueue and are consumed by sendPrompt. Events produced while no
    // prompt is active — the CLI emitting autonomously (a background job finishing, a
    // ScheduleWakeup firing) — go to autonomousQueue and are consumed by pollAutonomousEvent.
    // Routing by turnActive keeps autonomous output from being mis-delivered as the response to
    // the next user prompt.
    private final LinkedBlockingQueue<StreamEvent> turnQueue = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<StreamEvent> autonomousQueue = new LinkedBlockingQueue<>();
    private volatile boolean turnActive;
    private volatile String apiKey;

    /**
     * Creates a new CLI process manager.
     *
     * @param binary       the CLI binary name (e.g. "claude", "codex")
     * @param apiKeyEnvVar the environment variable name used to pass the API key to the process
     * @param config       the initial configuration for the subprocess
     */
    public CliProcess(String binary, String apiKeyEnvVar, CliConfig config) {
        this.binary = binary;
        this.apiKeyEnvVar = apiKeyEnvVar;
        this.config = config;
    }

    /**
     * Returns the current configuration.
     *
     * @return the active {@link CliConfig}
     */
    public CliConfig getConfig() { return config; }

    /**
     * Replaces the current configuration. Takes effect on the next process start.
     *
     * @param config the new configuration
     */
    public void setConfig(CliConfig config) { this.config = config; }

    /**
     * Sets the API key to be passed to the subprocess via its environment variable.
     *
     * @param key the API key value
     */
    public void setApiKey(String key) { this.apiKey = key; }

    /**
     * Returns the session ID from the most recent result event, or {@code null} if none.
     *
     * @return the last known session ID
     */
    public String getLastSessionId() { return lastSessionId; }

    /**
     * Clears the last known session ID. Called on /clear so the UI shows no session
     * until the next prompt establishes a new one.
     */
    public void clearLastSessionId() { this.lastSessionId = null; }

    /**
     * Checks whether the underlying OS process is still running.
     *
     * @return {@code true} if the process is alive
     */
    public boolean isAlive() { return currentProcess != null && currentProcess.isAlive(); }

    private void startProcess() throws IOException {
        List<String> cmd = buildCommand();
        logger.fine(() -> "Starting CLI: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("CLAUDECODE");
        pb.environment().remove("CLAUDE_CODE_ENTRYPOINT");
        if (apiKey != null && !apiKey.isBlank()) {
            pb.environment().put(apiKeyEnvVar, apiKey);
        }
        if (config.workingDir() != null) {
            pb.directory(new File(config.workingDir()));
        }
        pb.redirectErrorStream(false);
        currentProcess = pb.start();

        stdinStream = currentProcess.getOutputStream();
        BufferedReader stdoutReader =
                new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));

        // Background thread: read stdout continuously and route parsed events into the turn or
        // autonomous queue. Keeps running while the process is alive, picking up both prompted and
        // autonomous (background-job / ScheduleWakeup) turns.
        readerThread = Thread.ofVirtual().start(() -> {
            try {
                String line;
                while ((line = stdoutReader.readLine()) != null) {
                    line = stripAnsi(line).trim();
                    if (line.isEmpty() || !line.startsWith("{")) continue;
                    String logLine = line;
                    logger.info(() -> "stdout: " + logLine);
                    for (StreamEvent event : parser.parse(line)) {
                        if ("result".equals(event.type()) && event.sessionId() != null) {
                            lastSessionId = event.sessionId();
                        }
                        routeEvent(event);
                    }
                }
            } catch (IOException ignored) {}
        });

        Process proc = currentProcess;
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String msg = line;
                    logger.fine(() -> "stderr: " + msg);
                }
            } catch (IOException ignored) {}
        });
    }

    /**
     * Sends a prompt and streams response events to the callback.
     * The process stays alive after a turn completes.
     *
     * <p>Writes the user message to stdin, then reads events from {@code turnQueue}
     * (filled by the background reader thread while the turn is active) until a
     * {@code result} event is received.</p>
     */
    public int sendPrompt(String prompt, StreamCallback callback) throws IOException {
        return sendPrompt(prompt, List.of(), callback);
    }

    /**
     * Sends a prompt with attached images and streams response events to the callback.
     * See {@link #sendPrompt(String, StreamCallback)} for the turn protocol; {@code imageDataUrls}
     * are data URLs (e.g. {@code data:image/png;base64,...}) rendered as Claude image content
     * blocks alongside the text.
     */
    public int sendPrompt(String prompt, List<String> imageDataUrls, StreamCallback callback) throws IOException {
        if (currentProcess == null || !currentProcess.isAlive()) {
            turnQueue.clear();
            autonomousQueue.clear();
            startProcess();
        }

        // Mark the turn active before writing so the reader routes the response to turnQueue.
        beginTurn();
        writeUserMessage(prompt, imageDataUrls);

        sendingThread = Thread.currentThread();
        try {
            while (true) {
                StreamEvent event = pollTurnEvent(60_000);
                if (event == null) {
                    if (currentProcess != null && currentProcess.isAlive()) {
                        // Process is alive but slow (e.g. 529 retries in progress) — keep waiting.
                        // Do NOT return here: the response will eventually arrive and must reach the callback.
                        logger.info(binary + " CLI: no event in 60s but process is alive — continuing to wait...");
                        continue;
                    }
                    // Process has died — give up and discard any stale queued events.
                    turnActive = false;
                    turnQueue.clear();
                    autonomousQueue.clear();
                    if (callback != null) callback.onComplete(-1);
                    return -1;
                }
                if (callback != null) callback.onEvent(event);
                if ("result".equals(event.type())) return 0;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (callback != null) callback.onComplete(-1);
            return -1;
        } finally {
            sendingThread = null;
        }
    }

    /**
     * Polls for the next autonomous event (one produced while no {@link #sendPrompt} turn was
     * active — e.g. a background job finishing or a ScheduleWakeup firing). Consumed by the
     * ChatActor's idle monitor to surface such output as its own assistant turn.
     *
     * @param timeoutMs maximum wait in milliseconds; 0 means non-blocking
     * @return the next autonomous event, or {@code null} if none arrived within the timeout
     */
    public StreamEvent pollAutonomousEvent(long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0) return autonomousQueue.poll();
        return autonomousQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns whether any autonomous event is currently buffered. Non-blocking; safe to call
     * from any thread. The idle monitor uses this cheap check before committing to a drain.
     *
     * @return {@code true} if at least one autonomous event is waiting
     */
    public boolean hasAutonomousEvent() {
        return !autonomousQueue.isEmpty();
    }

    /** Marks a prompt turn as started so subsequent events route to the turn queue. */
    void beginTurn() { turnActive = true; }

    /** Returns whether a prompt turn is currently in flight (visible for testing). */
    boolean isTurnActive() { return turnActive; }

    /**
     * Routes one parsed event to the turn queue or the autonomous queue depending on whether a
     * prompt turn is in flight. The {@code result} event is the turn boundary: it belongs to the
     * active turn, and everything after it (until the next prompt) is autonomous. Flipping
     * {@code turnActive} here — in the single reader thread — closes the window in which a
     * post-result event could leak into the turn stream and be mis-delivered as the next prompt's
     * response. Uses {@code offer} on unbounded queues, so it never blocks.
     *
     * @param event the parsed stream event to route
     */
    void routeEvent(StreamEvent event) {
        if (turnActive) {
            turnQueue.offer(event);
            if ("result".equals(event.type())) turnActive = false;
        } else {
            autonomousQueue.offer(event);
        }
    }

    /**
     * Polls the turn queue for the next event of the in-flight prompt.
     *
     * @param timeoutMs maximum wait in milliseconds
     * @return the next turn event, or {@code null} if none arrived within the timeout
     * @throws InterruptedException if interrupted while waiting
     */
    StreamEvent pollTurnEvent(long timeoutMs) throws InterruptedException {
        return turnQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Destroys the running CLI process and resets all I/O handles.
     */
    public void cancel() {
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        Thread t = sendingThread;
        if (t != null) t.interrupt();
        turnActive = false;
        turnQueue.clear();
        autonomousQueue.clear();
        Process p = currentProcess;
        currentProcess = null;
        stdinStream = null;
        if (p != null && p.isAlive()) {
            p.destroy();
            logger.info(binary + " CLI process cancelled");
        }
    }

    /**
     * Writes a user message to the process stdin in stream-json format.
     *
     * @param text the message text to send
     * @throws IOException if the process stdin is not available or writing fails
     */
    public void writeUserMessage(String text) throws IOException {
        writeUserMessage(text, List.of());
    }

    /**
     * Writes a user message with attached images to the process stdin in stream-json format.
     *
     * @param text          the message text to send
     * @param imageDataUrls data URLs (e.g. {@code data:image/png;base64,...}); when empty, the
     *                      content is sent as a plain string, identical to {@link #writeUserMessage(String)}
     * @throws IOException if the process stdin is not available or writing fails
     */
    public void writeUserMessage(String text, List<String> imageDataUrls) throws IOException {
        if (stdinStream == null) throw new IOException("No active process stdin");
        String json = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
            + buildContentJson(text, imageDataUrls) + "}}\n";
        stdinStream.write(json.getBytes(StandardCharsets.UTF_8));
        stdinStream.flush();
    }

    static String buildContentJson(String text, List<String> imageDataUrls) {
        if (imageDataUrls == null || imageDataUrls.isEmpty()) {
            return escapeJsonString(text);
        }
        StringBuilder sb = new StringBuilder("[{\"type\":\"text\",\"text\":")
            .append(escapeJsonString(text)).append("}");
        for (String dataUrl : imageDataUrls) {
            sb.append(",{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":")
              .append(escapeJsonString(mediaTypeOf(dataUrl)))
              .append(",\"data\":")
              .append(escapeJsonString(base64DataOf(dataUrl)))
              .append("}}");
        }
        return sb.append("]").toString();
    }

    private static String mediaTypeOf(String dataUrl) {
        int colon = dataUrl.indexOf(':');
        int semicolon = dataUrl.indexOf(';');
        if (colon < 0 || semicolon < 0 || semicolon < colon) return "image/png";
        return dataUrl.substring(colon + 1, semicolon);
    }

    private static String base64DataOf(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        return comma < 0 ? dataUrl : dataUrl.substring(comma + 1);
    }

    /**
     * Sends a permission response back to the CLI process.
     *
     * <p>Claude Code CLI expects permission responses as a tool result in stream-json format.
     * The {@code response} should be one of: "yes", "yes-dont-ask-again", "no".</p>
     *
     * @param toolUseId the tool_use_id from the permission request
     * @param response  the user's answer ("yes", "yes-dont-ask-again", or "no")
     * @throws IOException if writing to the process stdin fails
     */
    public void writePermissionResponse(String toolUseId, String response) throws IOException {
        if (stdinStream == null) throw new IOException("No active process stdin");
        String normalised = normalisePermissionResponse(response);
        String json = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
            + "[{\"type\":\"tool_result\",\"tool_use_id\":"
            + escapeJsonString(toolUseId) + ",\"content\":"
            + escapeJsonString(normalised) + "}]}}\n";
        stdinStream.write(json.getBytes(StandardCharsets.UTF_8));
        stdinStream.flush();
        logger.info("Permission response sent: " + normalised + " for tool_use_id=" + toolUseId);
    }

    static String normalisePermissionResponse(String raw) {
        if (raw == null) return "no";
        return switch (raw.toLowerCase().trim()) {
            case "yes", "y", "1", "ok", "allow" -> "yes";
            case "yes-dont-ask-again", "yes, don't ask again",
                 "yes don't ask again", "always" -> "yes-dont-ask-again";
            default -> "no";
        };
    }

    List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        // The CLI writes to a pipe here, not a TTY, so libc switches its stdout from line
        // buffering to 4KB block buffering. Events then sit in the buffer instead of reaching
        // us as they are produced, and the whole turn arrives at once when the process flushes.
        // stdbuf -oL forces line buffering so each JSON event is delivered as it is written.
        cmd.add("stdbuf");
        cmd.add("-oL");
        cmd.add(binary);
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--verbose");

        if (config.model() != null) { cmd.add("--model"); cmd.add(config.model()); }
        // Left off entirely when unset, so the CLI applies its own default effort rather than
        // this program picking one and freezing it as the CLI's default changes.
        if (config.effort() != null && !config.effort().isBlank()) {
            cmd.add("--effort"); cmd.add(config.effort());
        }
        if (config.systemPrompt() != null) { cmd.add("--system-prompt"); cmd.add(config.systemPrompt()); }
        if (config.maxTurns() > 0) { cmd.add("--max-turns"); cmd.add(String.valueOf(config.maxTurns())); }
        if (config.sessionId() != null) { cmd.add("--resume"); cmd.add(config.sessionId()); }
        if (config.continueSession()) cmd.add("-c");
        if (config.permissionMode() != null && !config.permissionMode().isBlank()) {
            cmd.add("--permission-mode");
            cmd.add(config.permissionMode());
        }
        if (config.allowedTools() != null) {
            for (String tool : config.allowedTools()) { cmd.add("--allowedTools"); cmd.add(tool); }
        }
        // "" is a real value here: it disables every built-in tool, which is how a conversation
        // uses Claude as a bare model and runs this program's own tools instead
        // (CliHarnessProvider_260912_oo01).
        if (config.tools() != null) { cmd.add("--tools"); cmd.add(config.tools()); }
        return cmd;
    }

    static String stripAnsi(String s) {
        return ANSI_PATTERN.matcher(s).replaceAll("");
    }

    static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    public interface StreamCallback {
        void onEvent(StreamEvent event);
        default void onComplete(int exitCode) {}
    }
}
