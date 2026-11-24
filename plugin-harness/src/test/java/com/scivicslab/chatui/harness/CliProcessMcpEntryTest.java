package com.scivicslab.chatui.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link CliProcess} hands Claude Code the MCP entry of agent-connector-kernel's {@link HarnessLaunch}
 * (tasks/todo.md, Decisions 5 and stage 7). The tests of {@code HarnessLaunch} itself moved to agent-connector-kernel
 * with the class in stage 11.
 */
class CliProcessMcpEntryTest {

    @Test
    void claudeIsHandedTheMcpEntryOnlyWhenGivenOne(@TempDir Path dir) {
        CliProcess process = new CliProcess("claude", "ANTHROPIC_API_KEY",
                CliConfig.defaults("sonnet").withWorkingDir(dir.toString()));
        assertFalse(process.buildCommand().contains("--mcp-config"));
        process.setMcpEntry(new HarnessLaunch.McpEntry("http://127.0.0.1:41234/mcp", "s1", "k1"));
        List<String> cmd = process.buildCommand();
        int at = cmd.indexOf("--mcp-config");
        assertTrue(at > 0, cmd.toString());
        assertTrue(cmd.get(at + 1).contains("\"url\":\"http://127.0.0.1:41234/mcp\""), cmd.get(at + 1));
    }
}
