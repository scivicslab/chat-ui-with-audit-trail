package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Codex as a conversation's provider ({@code CliHarnessProvider_260912_oo01}).
 *
 * <p>Codex has no resident process to talk to: each turn is one {@code codex exec --json} run,
 * and the turns after the first are {@code codex exec resume <threadId> --json}, so the thread id
 * the first run reports is what this provider keeps — in memory and in its session file. The
 * prompt goes in on stdin (the {@code -} argument), the events come out on stdout as JSON Lines,
 * and the process exiting is the end of the turn.</p>
 *
 * <p>Codex runs its own tools: its command executions, file changes and MCP calls are reported
 * as {@code tool_use}/{@code tool_result} events for the conversation to record. Codex has no
 * switch that disables those tools, so this provider only exists with the harness tool
 * set.</p>
 */
public class CodexProvider implements LlmProvider {

    private static final Logger logger = Logger.getLogger(CodexProvider.class.getName());

    public static final String ID = "codex";

    /**
     * What a ChatGPT-account Codex offers (its {@code ~/.codex/models_cache.json} lists exactly
     * this; {@code gpt-5.4} and {@code gpt-5.2-codex} are refused with "not supported when using
     * Codex with a ChatGPT account"). A name typed into the model box is passed through as is.
     */
    private static final List<ModelEntry> MODELS = List.of(
        new ModelEntry("gpt-5.5", ID, null)
    );

    /** Codex's sandbox for the commands it runs; the project's working directory is what it may write. */
    private static final String SANDBOX_MODE = "workspace-write";

    private final Path sessionFile;
    private final Path workingDir;
    private final CodexEventParser parser = new CodexEventParser();
    private volatile String model;
    private volatile String threadId;
    private volatile Process currentProcess;

    /**
     * @param settings   process-wide harness settings
     * @param projectId  owning project's id, part of the session file name
     * @param chatId     conversation id, part of the session file name
     * @param workingDir the directory Codex works in, or {@code null} for this process's own
     */
    public CodexProvider(HarnessSettings settings, String projectId, String chatId, Path workingDir) {
        this.sessionFile = settings.sessionFile(projectId, chatId, ID);
        this.workingDir = workingDir;
        this.model = settings.codexModel();
        restoreSession();
    }

    /**
     * What Codex is told at the top of a turn's first prompt
     * ({@code HarnessPrefaceAndToolSplit_260912_oo01}): its own shell and file tools stay usable,
     * and the conversation's listed tools — web search among them, which Codex may lack — are
     * called with the {@code <invoke>} format.
     */
    static final String PREFACE = """
            You are running inside Codex, your own coding harness, which runs shell commands and \
            edits files itself. Use those directly, the way you always do, whenever they fit the \
            task. The tools listed below are this conversation's own tools, called only with the \
            <invoke> format described next; web_search and fetch among them reach the web even when \
            your harness cannot, and this conversation records what they return.

            """;

    @Override public String promptPreface() { return PREFACE; }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Codex"; }
    @Override public List<ModelEntry> getAvailableModels() { return MODELS; }
    @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.CLI; }
    @Override public String getCurrentModel() { return model; }
    @Override public void setModel(String model) { this.model = model; }
    @Override public String getSessionId() { return threadId; }
    @Override public String detectEnvApiKey() { return System.getenv("OPENAI_API_KEY"); }

    @Override
    public void cancel() {
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            logger.info("codex process cancelled");
        }
    }

    @Override
    public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
        if (model != null && !model.isBlank()) this.model = model;
        List<String> cmd = buildCommand();
        logger.fine(() -> "Starting codex: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workingDir != null) pb.directory(new File(workingDir.toString()));
        if (ctx.apiKey() != null && !ctx.apiKey().isBlank()) pb.environment().put("OPENAI_API_KEY", ctx.apiKey());
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            logger.log(Level.WARNING, "codex could not be started", e);
            emitter.accept(ChatEvent.error("codex could not be started: " + e.getMessage()));
            return;
        }
        currentProcess = process;

        Thread.ofVirtual().start(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    String msg = line;
                    logger.fine(() -> "codex stderr: " + msg);
                }
            } catch (IOException ignored) { }
        });

        boolean sawResult = false;
        boolean sawAssistant = false;
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.log(Level.WARNING, "codex stdin could not be written", e);
        }
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                ctx.onActivity().run();
                for (StreamEvent event : parser.parse(line)) {
                    switch (event.type()) {
                        case "system" -> {
                            if (event.sessionId() != null) saveSession(event.sessionId());
                        }
                        case "assistant" -> {
                            if (event.hasContent()) {
                                sawAssistant = true;
                                emitter.accept(ChatEvent.delta(event.content()));
                            }
                        }
                        case "thinking" -> emitter.accept(ChatEvent.thinking(event.hasContent() ? event.content() : "Thinking..."));
                        case "tool_use" -> emitter.accept(ChatEvent.toolUse(event.toolUseId(), event.toolName(), event.content()));
                        case "tool_result" -> emitter.accept(ChatEvent.toolResult(event.toolUseId(),
                                event.content() == null ? "" : event.content(), event.isError()));
                        case "error" -> emitter.accept(ChatEvent.error(event.content()));
                        case "result" -> sawResult = true;
                        default -> { }
                    }
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "codex stdout could not be read", e);
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = -1;
        } finally {
            currentProcess = null;
        }
        if (!sawResult && !sawAssistant && exit != 0) {
            emitter.accept(ChatEvent.error("codex exited with status " + exit + " before completing the turn"));
        }
        emitter.accept(ChatEvent.result(threadId, -1, -1, this.model, false));
    }

    /** The command of one turn: a fresh thread, or a resume of the one this conversation holds. */
    List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add("codex");
        cmd.add("exec");
        if (threadId != null) {
            cmd.add("resume");
            cmd.add(threadId);
        }
        cmd.add("--json");
        cmd.add("--skip-git-repo-check");
        cmd.add("-c");
        cmd.add("sandbox_mode=\"" + SANDBOX_MODE + "\"");
        if (model != null && !model.isBlank()) { cmd.add("-m"); cmd.add(model); }
        cmd.add("-");
        return cmd;
    }

    // ---- Session persistence ----

    private void restoreSession() {
        try {
            if (!Files.exists(sessionFile)) return;
            for (String line : Files.readAllLines(sessionFile)) {
                if (line.startsWith("sessionId=")) {
                    String id = line.substring("sessionId=".length()).trim();
                    if (!id.isEmpty()) threadId = id;
                } else if (line.startsWith("model=")) {
                    String m = line.substring("model=".length()).trim();
                    if (!m.isEmpty()) model = m;
                }
            }
            if (threadId != null) logger.info("Restored codex thread " + threadId + " from " + sessionFile);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read session file " + sessionFile, e);
        }
    }

    private void saveSession(String id) {
        threadId = id;
        try {
            Files.createDirectories(sessionFile.getParent());
            Files.writeString(sessionFile, "sessionId=" + id + "\nmodel=" + (model == null ? "" : model) + "\n");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to write session file " + sessionFile, e);
        }
    }
}
