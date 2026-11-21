package com.scivicslab.sshconnector;

import com.scivicslab.chatui.confine.PodmanConfinement;
import com.scivicslab.chatui.harness.HarnessLaunch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How ssh-connector starts its harness (tasks/todo.md, "Stage 11 spec", P1). */
class ConnectorLaunchTest {

    @TempDir
    Path base;

    private ConnectorSettings settings() {
        Map<String, String> m = new HashMap<>();
        m.put("ssh-connector.host.alias", "nig-a003");
        m.put("ssh-connector.host.name", "a003");
        m.put("ssh-connector.host.user", "u1");
        m.put("ssh-connector.key", base.resolve("keys/id_ed25519").toString());
        m.put("ssh-connector.work-dir", base.resolve("work").toString());
        m.put("ssh-connector.harness.kind", "opencode");
        m.put("ssh-connector.harness.image", "localhost/ack-opencode:1.19.6");
        m.put("ssh-connector.harness.model", "dummy-model");
        return ConnectorSettings.from(base.resolve("connector"), k -> Optional.ofNullable(m.get(k)));
    }

    @Test
    void podmanRunsTheConfinedHarnessWithItsArgumentsAndTheKeysOnlyInItsEnvironment() {
        ConnectorSettings s = settings();
        Path config = s.harnessDir().resolve("opencode.json");
        HarnessLaunch launch = ConnectorLaunch.launch(s, config, "MCPKEY-1", "LLMKEY-1", List.of("run", "a prompt"), false);
        List<String> cmd = launch.command();
        assertEquals(List.of("podman", "run", "--rm", "-i", "--network=none"), cmd.subList(0, 5));
        assertFalse(cmd.contains("-t"));
        assertEquals(List.of("localhost/ack-opencode:1.19.6", PodmanConfinement.RELAY, "opencode", "run", "a prompt"),
                cmd.subList(cmd.size() - 5, cmd.size()));
        assertTrue(cmd.contains(s.socket() + ":" + PodmanConfinement.SOCKET_DIR + "/" + PodmanConfinement.SOCKET), cmd.toString());
        assertTrue(cmd.contains(base.resolve("work") + ":" + PodmanConfinement.WORK_DIR), cmd.toString());
        for (String a : cmd) {
            assertFalse(a.contains("MCPKEY-1") || a.contains("LLMKEY-1"), "no key on the command line: " + a);
            assertFalse(a.contains(s.privateDir().toString()), "nothing of the private directory is mounted: " + a);
            assertFalse(a.contains("id_ed25519"), "the ssh key is not mounted: " + a);
        }
        assertEquals(Map.of(PodmanConfinement.LLM_KEY_VAR, "LLMKEY-1", PodmanConfinement.MCP_KEY_VAR, "MCPKEY-1"),
                launch.addedEnv());
        assertEquals(List.of(ConnectorLaunch.CREDENTIAL_VAR), launch.removedEnv());
    }

    @Test
    void onATerminalPodmanAlsoGetsATerminal() {
        ConnectorSettings s = settings();
        List<String> cmd = ConnectorLaunch.launch(s, s.harnessDir().resolve("opencode.json"), "k", "l", List.of(), true)
                .command();
        assertEquals(cmd.indexOf("-i") + 1, cmd.indexOf("-t"));
    }
}
