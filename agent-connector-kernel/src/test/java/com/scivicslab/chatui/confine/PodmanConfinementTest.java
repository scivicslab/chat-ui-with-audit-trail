package com.scivicslab.chatui.confine;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code podman run} that confines a harness (tasks/todo.md, Decisions 9 and 10; stage 9 part 1). Dummy keys only. */
class PodmanConfinementTest {

    private static PodmanConfinement.Launch launch(Path config) {
        return new PodmanConfinement.Launch("localhost/ack-codex:0.171.0", Path.of("/home/u/.agent-connector-kernel"),
                Path.of("/run/user/1000/agent-connector-kernel/s1"), Path.of("/home/u/project"), config,
                new CodexHarness(), "dummy-mcp-key", "dummy-llm-key");
    }

    @Test
    void networkNoneTheTwoSocketsTheWorkDirTheConfigAndTheRelayBeforeTheHarness() {
        List<String> args = PodmanConfinement.arguments(launch(Path.of("/run/user/1000/agent-connector-kernel/s1/config.toml")));
        assertEquals(List.of("podman", "run", "--rm", "-i", "--network=none", "--userns=keep-id", "--cap-drop=all",
                "--security-opt=no-new-privileges",
                "-v", "/run/user/1000/agent-connector-kernel/s1/mcp.sock:/run/agent-connector-kernel/mcp.sock",
                "-v", "/run/user/1000/agent-connector-kernel/s1/llm.sock:/run/agent-connector-kernel/llm.sock",
                "-v", "/home/u/project:/work", "-w", "/work",
                "-v", "/run/user/1000/agent-connector-kernel/s1/config.toml:/home/agent/.codex/config.toml:ro",
                "-e", "ACK_LLM_KEY", "-e", "ACK_MCP_KEY",
                "localhost/ack-codex:0.171.0", "/usr/local/bin/ack-relay", "codex"), args);
        assertFalse(args.stream().anyMatch(a -> a.contains("dummy")), "no key value on the command line");
        assertFalse(args.stream().anyMatch(a -> a.startsWith("--network=") && !a.equals("--network=none")));
    }

    @Test
    void nothingInsideTheKernelDirectoryIsMounted() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PodmanConfinement.arguments(launch(Path.of("/home/u/.agent-connector-kernel/run/config.toml"))));
        assertTrue(e.getMessage().contains("inside agent-connector-kernel's directory"), e.getMessage());
    }

    @Test
    void theKeysArePassedThroughPodmansEnvironment() {
        assertEquals(Map.of("ACK_LLM_KEY", "dummy-llm-key", "ACK_MCP_KEY", "dummy-mcp-key"),
                PodmanConfinement.environment(launch(Path.of("/tmp/config.toml"))));
    }
}
