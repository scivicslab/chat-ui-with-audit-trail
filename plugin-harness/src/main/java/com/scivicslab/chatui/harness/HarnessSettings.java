package com.scivicslab.chatui.harness;

import java.nio.file.Path;

/**
 * Process-wide settings every harness provider shares ({@code CliHarnessProvider_260912_oo01}).
 * Read from configuration by {@code ChatUiActorSystem} and handed to each provider it creates.
 *
 * @param permissionMode  Claude Code's {@code --permission-mode}; {@code bypassPermissions} by
 *                        default, the same as {@code quarkus-chat-ui}: the Turing workflow the
 *                        conversation runs under, not a permission dialog, is what constrains a turn
 * @param sessionDir      directory holding one session file per conversation and harness, so a
 *                        provider created again for the same conversation resumes its session
 * @param httpPort        this instance's HTTP port, part of every session file's name
 * @param claudeModel     default model alias for a new Claude Code conversation
 * @param codexModel      default model for a new Codex conversation, or {@code null} for Codex's own
 */
public record HarnessSettings(
    String permissionMode,
    Path sessionDir,
    int httpPort,
    String claudeModel,
    String codexModel
) {

    /** @return the session file for one conversation and harness */
    public Path sessionFile(String projectId, String chatId, String harnessId) {
        String name = httpPort + "-" + safe(projectId) + "-" + safe(chatId) + "-" + harnessId;
        return sessionDir.resolve(name);
    }

    private static String safe(String s) {
        return s == null ? "null" : s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
