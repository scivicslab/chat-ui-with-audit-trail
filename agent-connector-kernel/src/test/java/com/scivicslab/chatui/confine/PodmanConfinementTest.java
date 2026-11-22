package com.scivicslab.chatui.confine;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code podman run} that confines a harness (tasks/todo.md, Decisions 9, 10, 12 and 13; stage 9 part 1, step 3). Dummy keys only. */
class PodmanConfinementTest {

    private static PodmanConfinement.Launch launch(Path config) {
        return new PodmanConfinement.Launch("localhost/ack-codex:0.171.0", Path.of("/home/u/.agent-connector-kernel"),
                Path.of("/run/user/1000/agent-connector-kernel/s1"), Path.of("/home/u/project"), config,
                new CodexHarness(), "dummy-mcp-key", "dummy-llm-key");
    }

    @Test
    void networkNoneTheOneSocketTheWorkDirTheConfigAndTheRelayBeforeTheHarness() {
        List<String> args = PodmanConfinement.arguments(launch(Path.of("/run/user/1000/agent-connector-kernel/s1/config.toml")));
        assertEquals(List.of("podman", "run", "--rm", "-i", "--network=none", "--userns=keep-id:uid=1001,gid=1001", "--cap-drop=all",
                "--security-opt=no-new-privileges",
                "-v", "/run/user/1000/agent-connector-kernel/s1/agent-connector-kernel.sock:/run/agent-connector-kernel/agent-connector-kernel.sock",
                "-v", "/home/u/project:/work", "-w", "/work",
                "-v", "/run/user/1000/agent-connector-kernel/s1/config.toml:/home/agent/.codex/config.toml:ro",
                "-e", "ACK_LLM_KEY", "-e", "ACK_MCP_KEY",
                "localhost/ack-codex:0.171.0", "/usr/local/bin/ack-relay", "codex"), args);
        assertFalse(args.stream().anyMatch(a -> a.contains("dummy")), "no key value on the command line");
        assertFalse(args.stream().anyMatch(a -> a.startsWith("--network=") && !a.equals("--network=none")));
    }

    /**
     * The Quarkus HTTP server has one domain socket (https://quarkus.io/guides/vertx-reference#use-a-unix-domain-socket),
     * so the MCP server and the LLM proxy share it; the proxy is at /llm/&lt;scope&gt;/v1 (Decision 13).
     */
    @Test
    void theMcpServerAndTheProxyAreReachedThroughTheOneSocket() {
        assertEquals("http://127.0.0.1:18765/mcp", PodmanConfinement.mcpUrl());
        assertEquals("http://127.0.0.1:18766/llm/s1/v1", PodmanConfinement.llmBaseUrl("s1"));
        assertEquals("agent-connector-kernel.sock", PodmanConfinement.SOCKET);
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
