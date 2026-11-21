package com.scivicslab.chatui.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what chat-ui hands the claude and codex CLIs today: the command line and the environment
 * (CliHarnessProvider_260912_oo01; tasks/todo.md stage 7). Written before HarnessLaunch was extracted, and it must
 * pass both before and after.
 */
class HarnessLaunchPinTest {

    /** The configuration ClaudeCodeProvider builds for a new conversation (ClaudeCodeProvider lines 69-74). */
    private static CliConfig claudeConfig(Path dir) {
        return CliConfig.defaults("sonnet").withPermissionMode("bypassPermissions").withWorkingDir(dir.toString());
    }

    @Test
    void claudeHarnessToolSet(@TempDir Path dir) {
        assertEquals(List.of("stdbuf", "-oL", "claude", "--output-format", "stream-json", "--input-format", "stream-json",
                        "--verbose", "--model", "sonnet", "--permission-mode", "bypassPermissions"),
                new CliProcess("claude", "ANTHROPIC_API_KEY", claudeConfig(dir)).buildCommand());
    }

    @Test
    void claudeFullToolSetResumedWithEffort(@TempDir Path dir) {
        CliConfig config = claudeConfig(dir).withTools("").withSessionId("sess-1").withEffort("high");
        assertEquals(List.of("stdbuf", "-oL", "claude", "--output-format", "stream-json", "--input-format", "stream-json",
                        "--verbose", "--model", "sonnet", "--effort", "high", "--resume", "sess-1",
                        "--permission-mode", "bypassPermissions", "--tools", ""),
                new CliProcess("claude", "ANTHROPIC_API_KEY", config).buildCommand());
    }

    @Test
    void codexFreshAndResumed(@TempDir Path dir) {
        HarnessSettings settings = new HarnessSettings("bypassPermissions", dir.resolve("sessions"), 18090, "sonnet",
                "gpt-5.5", dir.resolve("claude-projects"));
        CodexProvider codex = new CodexProvider(settings, "project1", "01", dir);
        assertEquals(List.of("codex", "exec", "--json", "--skip-git-repo-check", "-c", "sandbox_mode=\"workspace-write\"",
                "-m", "gpt-5.5", "-"), codex.buildCommand());
    }

    /**
     * The environment and working directory the CLI process starts with: the API key under its variable when one is
     * set, Claude Code's own nesting variables removed, and the conversation's working directory.
     */
    @Test
    void claudeEnvironmentAndWorkingDirectory(@TempDir Path dir) throws Exception {
        Path seen = dir.resolve("seen.txt");
        Path script = dir.resolve("fake-claude");
        Files.writeString(script, "#!/bin/bash\n"
                + "read line\n"
                + "{ echo \"cwd=$(pwd)\"; env; } > '" + seen + "'\n"
                + "echo '{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"ok\",\"session_id\":\"s\"}'\n"
                + "exit 0\n");
        assertTrue(script.toFile().setExecutable(true));
        CliProcess process = new CliProcess(script.toString(), "ANTHROPIC_API_KEY",
                CliConfig.defaults(null).withWorkingDir(dir.toString()));
        process.setApiKey("pinned-key");
        process.sendPrompt("hello", new CopyOnWriteArrayList<>()::add);
        String env = Files.readString(seen);
        assertTrue(env.contains("cwd=" + dir.toRealPath()), env);
        assertTrue(env.contains("\nANTHROPIC_API_KEY=pinned-key\n"), env);
        assertFalse(env.contains("\nCLAUDECODE="), env);
        assertFalse(env.contains("\nCLAUDE_CODE_ENTRYPOINT="), env);
    }
}
