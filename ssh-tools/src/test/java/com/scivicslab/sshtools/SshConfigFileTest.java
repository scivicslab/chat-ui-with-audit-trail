package com.scivicslab.sshtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ssh-connector's own ssh_config (tasks/todo.md, "Stage 11 spec", P4). */
class SshConfigFileTest {

    @TempDir
    Path base;

    private static final List<String> REQUIRED = List.of("HostName", "User", "IdentityFile", "IdentitiesOnly yes",
            "BatchMode yes", "StrictHostKeyChecking accept-new", "UserKnownHostsFile", "GlobalKnownHostsFile /dev/null");

    @Test
    void writtenOwnerOnlyWithEveryRequiredKeyInEveryBlock() throws Exception {
        Path key = base.resolve("id_ed25519");
        Path dir = base.resolve("ssh");
        Path config = SshConfigFile.write(dir, List.of(
                new SshConfigFile.HostEntry("nig-gw", "gw.example.org", "u1", 0, key, null),
                new SshConfigFile.HostEntry("nig-a003", "a003", "u1", 2222, key, "nig-gw")));
        assertEquals(dir.resolve(SshConfigFile.CONFIG), config);
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config)));
        Path known = dir.resolve(SshConfigFile.KNOWN_HOSTS);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(known)));
        assertEquals(0, Files.size(known), "known_hosts starts empty");

        String text = Files.readString(config);
        String[] blocks = text.split("(?m)^Host ");
        assertEquals(3, blocks.length, text);
        for (int i = 1; i < blocks.length; i++) {
            for (String required : REQUIRED) assertTrue(blocks[i].contains("    " + required), required + " in " + blocks[i]);
            assertTrue(blocks[i].contains("    UserKnownHostsFile " + known), blocks[i]);
            assertTrue(blocks[i].contains("    IdentityFile " + key), blocks[i]);
        }
        assertTrue(blocks[2].contains("    Port 2222") && blocks[2].contains("    ProxyJump nig-gw"), blocks[2]);
        assertTrue(!blocks[1].contains("Port") && !blocks[1].contains("ProxyJump"), blocks[1]);
    }

    @Test
    void anOpenDirectoryIsRefusedAndLeftAsItIs() throws Exception {
        Path dir = Files.createDirectory(base.resolve("open"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class, () -> SshConfigFile.write(dir, List.of(
                new SshConfigFile.HostEntry("h", "127.0.0.1", "u", 0, base.resolve("k"), null))));
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        assertTrue(Files.notExists(dir.resolve(SshConfigFile.CONFIG)));
    }

    @Test
    void valuesThatWouldChangeTheFileAreRefused() {
        Path key = base.resolve("k");
        for (SshConfigFile.HostEntry bad : List.of(
                new SshConfigFile.HostEntry("h h", "127.0.0.1", "u", 0, key, null),
                new SshConfigFile.HostEntry("h", "127.0.0.1\n    ProxyCommand x", "u", 0, key, null),
                new SshConfigFile.HostEntry("h", "127.0.0.1", "u\"", 0, key, null),
                new SshConfigFile.HostEntry("h", "127.0.0.1", "", 0, key, null),
                new SshConfigFile.HostEntry("h", "127.0.0.1", "u#", 0, key, null),
                new SshConfigFile.HostEntry("h", "127.0.0.1", "u", 0, key, "missing"))) {
            assertThrows(IllegalArgumentException.class, () -> SshConfigFile.text(List.of(bad), base.resolve("kh")),
                    bad.toString());
        }
    }
}
