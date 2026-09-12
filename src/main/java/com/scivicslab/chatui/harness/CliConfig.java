package com.scivicslab.chatui.harness;

/**
 * Start-up options of one Claude Code process ({@code CliHarnessProvider_260912_oo01}). Immutable;
 * every {@code with*} returns a copy. Applied by {@link CliProcess#buildCommand()} the next time the
 * process starts.
 *
 * @param model          model alias or id passed as {@code --model}, or {@code null} for the CLI's own
 * @param systemPrompt   {@code --system-prompt}, or {@code null}
 * @param maxTurns       {@code --max-turns}, or 0 for no limit
 * @param workingDir     the process's working directory, or {@code null} for this process's own
 * @param sessionId      session to {@code --resume}, or {@code null} for a new one
 * @param continueSession whether to pass {@code -c}
 * @param allowedTools   {@code --allowedTools} entries, or {@code null}
 * @param permissionMode {@code --permission-mode}, or {@code null} for the CLI's own
 * @param effort         {@code --effort}, or {@code null} for the CLI's own
 * @param tools          {@code --tools} value ({@code ""} disables every built-in tool), or
 *                       {@code null} to leave the built-in set as the CLI has it
 */
public record CliConfig(
    String model,
    String systemPrompt,
    int maxTurns,
    String workingDir,
    String sessionId,
    boolean continueSession,
    String[] allowedTools,
    String permissionMode,
    String effort,
    String tools
) {

    /** @return a configuration with only the model set */
    public static CliConfig defaults(String defaultModel) {
        return new CliConfig(defaultModel, null, 0, null, null, false, null, null, null, null);
    }

    public CliConfig withModel(String newModel) {
        return new CliConfig(newModel, systemPrompt, maxTurns, workingDir, sessionId, continueSession,
                allowedTools, permissionMode, effort, tools);
    }

    public CliConfig withSessionId(String newSessionId) {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, newSessionId, continueSession,
                allowedTools, permissionMode, effort, tools);
    }

    public CliConfig withContinueSession() {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, sessionId, true,
                allowedTools, permissionMode, effort, tools);
    }

    public CliConfig withMaxTurns(int newMaxTurns) {
        return new CliConfig(model, systemPrompt, newMaxTurns, workingDir, sessionId, continueSession,
                allowedTools, permissionMode, effort, tools);
    }

    public CliConfig withWorkingDir(String newWorkingDir) {
        return new CliConfig(model, systemPrompt, maxTurns, newWorkingDir, sessionId, continueSession,
                allowedTools, permissionMode, effort, tools);
    }

    public CliConfig withAllowedTools(String... newAllowedTools) {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, sessionId, continueSession,
                newAllowedTools, permissionMode, effort, tools);
    }

    public CliConfig withEffort(String newEffort) {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, sessionId, continueSession,
                allowedTools, permissionMode, newEffort, tools);
    }

    public CliConfig withPermissionMode(String newPermissionMode) {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, sessionId, continueSession,
                allowedTools, newPermissionMode, effort, tools);
    }

    public CliConfig withTools(String newTools) {
        return new CliConfig(model, systemPrompt, maxTurns, workingDir, sessionId, continueSession,
                allowedTools, permissionMode, effort, newTools);
    }
}
