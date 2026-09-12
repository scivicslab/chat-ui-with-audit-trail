package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Claude Code as a conversation's provider ({@code CliHarnessProvider_260912_oo01}).
 *
 * <p>One {@code claude} process per conversation, driven over stream-json on stdin/stdout and kept
 * alive between turns; the session id it reports is written to a file so a provider created again
 * for the same conversation resumes it with {@code --resume}. Ported from {@code quarkus-chat-ui}'s
 * {@code CliLlmProvider}, without its slash-command handler.</p>
 *
 * <p>Inside one {@link #sendPrompt} the harness runs its own tools as often as it likes. Each such
 * call reaches the conversation as a {@code tool_use} event and its result as a {@code tool_result}
 * event, so the conversation can record them to the I/O log; with {@link ToolSet#FULL} the
 * built-in tools are disabled ({@code --tools ""}) and the conversation's own tools serve instead.</p>
 */
public class ClaudeCodeProvider implements LlmProvider {

    private static final Logger logger = Logger.getLogger(ClaudeCodeProvider.class.getName());

    public static final String ID = "claude";

    /** Aliases rather than dated ids: the CLI resolves each to the current model of that line. */
    private static final List<ModelEntry> MODELS = List.of(
        new ModelEntry("fable", ID, null),
        new ModelEntry("opus", ID, null),
        new ModelEntry("sonnet", ID, null),
        new ModelEntry("haiku", ID, null)
    );

    private final CliProcess cliProcess;
    private final Path sessionFile;
    private final ToolSet toolSet;
    private final Set<String> pendingPermissionIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> pendingPlanApprovalIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * @param settings   process-wide harness settings
     * @param projectId  owning project's id, part of the session file name
     * @param chatId     conversation id, part of the session file name
     * @param workingDir the directory the harness works in, or {@code null} for this process's own
     * @param toolSet    the conversation's tool set; {@link ToolSet#FULL} disables the built-in tools
     */
    public ClaudeCodeProvider(HarnessSettings settings, String projectId, String chatId,
                              Path workingDir, ToolSet toolSet) {
        this.toolSet = toolSet;
        this.sessionFile = settings.sessionFile(projectId, chatId, ID);
        CliConfig config = CliConfig.defaults(settings.claudeModel());
        if (settings.permissionMode() != null && !settings.permissionMode().isBlank()) {
            config = config.withPermissionMode(settings.permissionMode().trim());
        }
        if (workingDir != null) config = config.withWorkingDir(workingDir.toString());
        if (toolSet == ToolSet.FULL) config = config.withTools("");
        config = restoreSession(config);
        this.cliProcess = new CliProcess("claude", "ANTHROPIC_API_KEY", config);
    }

    /** @return the conversation's tool set this provider was created for */
    public ToolSet toolSet() { return toolSet; }

    /**
     * What Claude Code must be told at the top of a turn's first prompt when it runs with its own
     * tools ({@code HarnessPrefaceAndToolSplit_260912_oo01}). Without it the harness read the
     * conversation's "Available tools" list as everything it may use and declined to search the
     * web; and its WebSearch and WebFetch are deferred tools, absent until loaded with ToolSearch,
     * which it reports as "no web tool" unless told how to load them. With the built-in tools
     * switched off ({@link ToolSet#FULL}) there is nothing to say.
     */
    static final String PREFACE = """
            You are running inside Claude Code, your own coding harness, which has its own tools: \
            reading and writing files, running shell commands, and web search and web fetch \
            (WebSearch and WebFetch). Some of your tools are deferred: if WebSearch or WebFetch is \
            not yet loaded, load it first with ToolSearch (query "select:WebSearch,WebFetch") and \
            then call it. Use your own tools directly, the way you always do, whenever they fit the \
            task — including questions about the outside world such as news, prices or the weather. \
            The tools listed below are this conversation's own tools, NOT tools of your harness: \
            calling one of them as a harness tool fails with "No such tool available". To use one, \
            write the <invoke> block described next as text in your reply and end the reply there; \
            this conversation runs it and sends the result back. Either path is fine, and this \
            conversation records what it returns.

            """;

    @Override
    public String promptPreface() {
        return toolSet == ToolSet.FULL ? "" : PREFACE;
    }

    // ---- LlmProvider ----

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Claude Code"; }
    @Override public List<ModelEntry> getAvailableModels() { return MODELS; }
    @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.CLI; }
    @Override public String getCurrentModel() { return cliProcess.getConfig().model(); }
    @Override public void setModel(String model) { cliProcess.setConfig(cliProcess.getConfig().withModel(model)); }
    @Override public String getSessionId() { return cliProcess.getLastSessionId(); }
    @Override public String detectEnvApiKey() { return System.getenv("ANTHROPIC_API_KEY"); }
    @Override public void cancel() { cliProcess.cancel(); }

    @Override
    public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
        if (ctx.apiKey() != null) cliProcess.setApiKey(ctx.apiKey());
        if (model != null && !model.equals(cliProcess.getConfig().model())) {
            cliProcess.setConfig(cliProcess.getConfig().withModel(model));
        }
        if (!cliProcess.isAlive()) {
            String lastSessionId = cliProcess.getLastSessionId();
            if (lastSessionId != null) {
                cliProcess.setConfig(cliProcess.getConfig().withSessionId(lastSessionId));
            }
        }

        final boolean[] staleSession = {false};
        final boolean[] sawAssistant = {false};
        try {
            cliProcess.sendPrompt(prompt, ctx.imageDataUrls(), event -> {
                ctx.onActivity().run();
                dispatch(event, emitter, staleSession, sawAssistant);
            });
        } catch (IOException e) {
            logger.log(Level.WARNING, "claude CLI failed", e);
            emitter.accept(ChatEvent.error("claude CLI error: " + e.getMessage()));
            return;
        }

        if (staleSession[0]) {
            logger.warning("Stale claude session detected; clearing it and retrying once");
            cliProcess.cancel();
            deleteSessionFile();
            cliProcess.setConfig(cliProcess.getConfig().withSessionId(null));
            emitter.accept(ChatEvent.thinking("Session expired. Starting new session..."));
            try {
                final boolean[] retrySawAssistant = {false};
                cliProcess.sendPrompt(prompt, ctx.imageDataUrls(), event -> {
                    ctx.onActivity().run();
                    dispatch(event, emitter, new boolean[]{false}, retrySawAssistant);
                });
            } catch (IOException e) {
                emitter.accept(ChatEvent.error("claude CLI error on retry: " + e.getMessage()));
            }
        }
    }

    /**
     * Maps one stream event to the {@link ChatEvent}s the conversation understands. Text streams as
     * {@code delta}; the harness's own tool calls and their results pass through as
     * {@code tool_use}/{@code tool_result} for the conversation to record.
     */
    void dispatch(StreamEvent event, Consumer<ChatEvent> emitter, boolean[] staleSession, boolean[] sawAssistant) {
        switch (event.type()) {
            case "assistant" -> {
                if (event.content() != null && !event.content().isEmpty()) {
                    sawAssistant[0] = true;
                    emitter.accept(ChatEvent.delta(event.content()));
                }
            }
            case "thinking" -> emitter.accept(ChatEvent.thinking(
                    event.hasContent() ? event.content() : "Thinking..."));
            case "tool_use" -> emitter.accept(ChatEvent.toolUse(event.toolUseId(), event.toolName(), event.content()));
            case "tool_result" -> {
                // ExitPlanMode is surfaced as a prompt from its tool_use block; the CLI then
                // auto-denies the tool with this result, which is not an error of the turn.
                if (event.isError() && "Exit plan mode?".equals(
                        event.content() != null ? event.content().trim() : "")) {
                    return;
                }
                emitter.accept(ChatEvent.toolResult(event.toolUseId(),
                        event.content() == null ? "" : event.content(), event.isError()));
            }
            case "system" -> {
                if (event.sessionId() != null) saveSession(event.sessionId());
            }
            case "result" -> {
                // Newer CLIs may put the whole answer only in the result event; older ones always
                // streamed it as assistant events. Send it once either way.
                if (!sawAssistant[0] && event.content() != null && !event.content().isBlank()) {
                    emitter.accept(ChatEvent.delta(event.content()));
                }
                if (event.sessionId() != null) saveSession(event.sessionId());
                emitter.accept(ChatEvent.result(event.sessionId(), event.costUsd(), event.durationMs(),
                        cliProcess.getConfig().model(), false));
            }
            case "error" -> {
                if (event.content() != null
                        && event.content().contains("No conversation found with session ID")) {
                    staleSession[0] = true;
                } else {
                    emitter.accept(ChatEvent.error(event.content()));
                }
            }
            case "prompt" -> {
                if ("permission".equals(event.promptType()) && event.promptId() != null) {
                    pendingPermissionIds.add(event.promptId());
                } else if ("exit_plan_mode".equals(event.promptType()) && event.promptId() != null) {
                    pendingPlanApprovalIds.add(event.promptId());
                }
                emitter.accept(ChatEvent.prompt(
                        event.promptId(), event.content(), event.promptType(), event.options()));
            }
            default -> { }
        }
    }

    // ---- Interactive prompts ----

    @Override public boolean supportsInteractivePrompts() { return true; }

    @Override
    public void respond(String promptId, String response) throws IOException {
        if (pendingPermissionIds.remove(promptId)) {
            cliProcess.writePermissionResponse(promptId, response);
        } else {
            cliProcess.writeUserMessage(response);
        }
    }

    @Override
    public boolean isPlanApproval(String promptId) {
        return promptId != null && pendingPlanApprovalIds.contains(promptId);
    }

    @Override
    public void clearPlanApproval(String promptId) {
        pendingPlanApprovalIds.remove(promptId);
    }

    // ---- Autonomous events ----

    private static final long AUTONOMOUS_EVENT_TIMEOUT_MS = 5_000;

    @Override public boolean supportsAutonomousEvents() { return true; }
    @Override public boolean hasAutonomousActivity() { return cliProcess.hasAutonomousEvent(); }

    @Override
    public boolean drainAutonomousActivity(Consumer<ChatEvent> emitter) {
        if (!cliProcess.hasAutonomousEvent()) return false;
        final boolean[] staleSession = {false};
        final boolean[] sawAssistant = {false};
        boolean surfaced = false;
        try {
            while (true) {
                StreamEvent event = cliProcess.pollAutonomousEvent(AUTONOMOUS_EVENT_TIMEOUT_MS);
                if (event == null) break;
                dispatch(event, emitter, staleSession, sawAssistant);
                surfaced = true;
                if ("result".equals(event.type())) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return surfaced;
    }

    // ---- Session persistence ----

    private CliConfig restoreSession(CliConfig config) {
        try {
            if (!Files.exists(sessionFile)) return config;
            String savedSessionId = null;
            String savedModel = null;
            for (String line : Files.readAllLines(sessionFile)) {
                if (line.startsWith("sessionId=")) savedSessionId = line.substring("sessionId=".length()).trim();
                else if (line.startsWith("model=")) savedModel = line.substring("model=".length()).trim();
            }
            if (savedSessionId != null && !savedSessionId.isEmpty()) {
                config = config.withSessionId(savedSessionId);
                logger.info("Restored claude session " + savedSessionId + " from " + sessionFile);
            }
            if (savedModel != null && !savedModel.isEmpty()) {
                config = config.withModel(savedModel);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to read session file " + sessionFile, e);
        }
        return config;
    }

    private void saveSession(String sessionId) {
        try {
            Files.createDirectories(sessionFile.getParent());
            Files.writeString(sessionFile,
                    "sessionId=" + sessionId + "\nmodel=" + cliProcess.getConfig().model() + "\n");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to write session file " + sessionFile, e);
        }
    }

    private void deleteSessionFile() {
        try {
            Files.deleteIfExists(sessionFile);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to delete session file " + sessionFile, e);
        }
    }
}
