package com.scivicslab.sshconnector;

import com.scivicslab.chatui.confine.OpenCodeHarness;
import com.scivicslab.sshtools.SlurmSettings;
import com.scivicslab.sshtools.SshConfigFile;
import com.scivicslab.sshtools.SshLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ssh-connector's one settings file (tasks/todo.md, "Stage 11 spec"). */
class ConnectorSettingsTest {

    @TempDir
    Path base;

    private Map<String, String> minimal() {
        Map<String, String> m = new HashMap<>();
        m.put("ssh-connector.host.alias", "nig-a003");
        m.put("ssh-connector.host.name", "a003");
        m.put("ssh-connector.host.user", "u1");
        m.put("ssh-connector.key", base.resolve("keys/id_ed25519").toString());
        m.put("ssh-connector.work-dir", base.resolve("work").toString());
        return m;
    }

    private ConnectorSettings settings(Map<String, String> m) {
        return ConnectorSettings.from(base.resolve("connector"), k -> Optional.ofNullable(m.get(k)));
    }

    @Test
    void theRequiredSettingsAloneTakeTheDefaults() {
        ConnectorSettings s = settings(minimal());
        assertEquals("ssh", s.ssh());
        assertEquals(SlurmSettings.DEFAULT, s.slurm());
        assertEquals(SshLimits.DEFAULT, s.limits());
        assertEquals("none", s.harness());
        assertNull(s.jump());
        assertNull(s.podmanHarness());
        assertEquals(List.of(new SshConfigFile.HostEntry("nig-a003", "a003", "u1", 0, base.resolve("keys/id_ed25519"),
                null)), s.hostEntries());
        Path dir = base.resolve("connector");
        assertEquals(dir.resolve("private"), s.privateDir());
        assertEquals(dir.resolve("private/ssh"), s.sshDir());
        assertEquals(dir.resolve("sockets"), s.socketDir());
        assertEquals(dir.resolve("sockets/agent-connector-kernel.sock"), s.socket());
        assertEquals(dir.resolve("harness"), s.harnessDir());
    }

    @Test
    void aJumpHostTheLimitsTheSlurmCommandsAndTheHarness() {
        Map<String, String> m = minimal();
        m.put("ssh-connector.host.port", "2222");
        m.put("ssh-connector.jump.alias", "nig-gw");
        m.put("ssh-connector.jump.name", "gw.example.org");
        m.put("ssh-connector.jump.user", "u1");
        m.put("ssh-connector.limits.time-seconds", "30");
        m.put("ssh-connector.limits.output-bytes", "4096");
        m.put("ssh-connector.limits.file-bytes", "8192");
        m.put("ssh-connector.slurm.sbatch", "/opt/slurm/bin/sbatch");
        m.put("ssh-connector.slurm.jobs-dir", "/home/u1/jobs");
        m.put("ssh-connector.harness.kind", "opencode");
        m.put("ssh-connector.harness.image", "localhost/ack-opencode:1.19.6");
        m.put("ssh-connector.harness.model", "dummy-model");
        ConnectorSettings s = settings(m);
        Path key = base.resolve("keys/id_ed25519");
        assertEquals(List.of(new SshConfigFile.HostEntry("nig-gw", "gw.example.org", "u1", 0, key, null),
                new SshConfigFile.HostEntry("nig-a003", "a003", "u1", 2222, key, "nig-gw")), s.hostEntries());
        assertEquals(new SshLimits(Duration.ofSeconds(30), 4096, 8192), s.limits());
        assertEquals(new SlurmSettings("/opt/slurm/bin/sbatch", "squeue", "sacct", "scancel", "/home/u1/jobs"), s.slurm());
        assertInstanceOf(OpenCodeHarness.class, s.podmanHarness());
        assertEquals("localhost/ack-opencode:1.19.6", s.image());
    }

    @Test
    void aMissingRequiredSettingIsNamed() {
        for (String key : List.of("ssh-connector.host.alias", "ssh-connector.host.name", "ssh-connector.host.user",
                "ssh-connector.key", "ssh-connector.work-dir")) {
            Map<String, String> m = minimal();
            m.remove(key);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> settings(m));
            assertTrue(e.getMessage().contains(key), e.getMessage());
        }
        Map<String, String> m = minimal();
        m.put("ssh-connector.harness.kind", "opencode");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> settings(m));
        assertTrue(e.getMessage().contains("ssh-connector.harness.image"), e.getMessage());
    }

    @Test
    void theWorkingDirectoryMayNotOverlapTheDirectoryOrHoldTheKey() {
        for (Map.Entry<String, String> bad : List.of(
                Map.entry("ssh-connector.work-dir", base.resolve("connector/work").toString()),
                Map.entry("ssh-connector.work-dir", base.toString()),
                Map.entry("ssh-connector.key", base.resolve("work/id_ed25519").toString()),
                Map.entry("ssh-connector.harness.kind", "bash"),
                Map.entry("ssh-connector.host.port", "port"),
                Map.entry("ssh-connector.limits.time-seconds", "0"))) {
            Map<String, String> m = minimal();
            m.put(bad.getKey(), bad.getValue());
            assertThrows(IllegalArgumentException.class, () -> settings(m), bad.toString());
        }
    }
}
