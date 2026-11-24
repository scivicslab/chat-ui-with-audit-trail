package com.scivicslab.sshconnector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The settings the startup guard checks are the ones Quarkus will use (tasks/todo.md, "ssh-connector's startup guard"). */
class StartupSettingsTest {

    @TempDir
    Path base;

    private Path dir() throws Exception {
        Path dir = Files.createDirectory(base.resolve("connector"));
        Files.writeString(dir.resolve(ConnectorSettings.FILE), String.join("\n",
                "ssh-connector.host.alias=nig-a003",
                "ssh-connector.host.name=a003",
                "ssh-connector.host.user=u1",
                "ssh-connector.key=" + base.resolve("keys/id_ed25519"),
                "ssh-connector.work-dir=" + base.resolve("work"), ""));
        return dir;
    }

    @Test
    void theFileIsReadWhenNothingOverridesIt() throws Exception {
        ConnectorSettings s = StartupSettings.read(dir(), k -> null, k -> null);
        assertEquals("a003", s.host().hostName());
        assertEquals("nig-a003", s.host().alias());
    }

    @Test
    void aSystemPropertyWinsOverTheEnvironmentWhichWinsOverTheFile() throws Exception {
        Path dir = dir();
        Map<String, String> env = Map.of("SSH_CONNECTOR_HOST_NAME", "from-env");
        assertEquals("from-env", StartupSettings.read(dir, k -> null, env::get).host().hostName());
        Map<String, String> props = Map.of("ssh-connector.host.name", "from-property");
        assertEquals("from-property", StartupSettings.read(dir, props::get, env::get).host().hostName());
    }

    @Test
    void theEnvironmentNameFollowsMicroProfileConfig() {
        assertEquals("SSH_CONNECTOR_JUMP_NAME", StartupSettings.envName("ssh-connector.jump.name"));
    }
}
