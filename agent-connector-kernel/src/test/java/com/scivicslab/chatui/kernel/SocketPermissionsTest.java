package com.scivicslab.chatui.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Quarkus HTTP server's Unix domain socket is the owner's alone: a 0600 socket in a 0700 directory, and a directory
 * others can read or enter is refused rather than changed (tasks/todo.md, Decisions 10 and 12, step 3).
 */
class SocketPermissionsTest {

    @Test
    void aMissingDirectoryIsCreatedOwnerOnly(@TempDir Path root) throws IOException {
        Path dir = root.resolve("sockets");
        SocketPermissions.requireOwnerOnlyDirectory(dir.resolve("agent-connector-kernel.sock"));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    }

    @Test
    void aDirectoryOthersCanEnterIsRefused(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectory(root.resolve("open"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        IOException e = assertThrows(IOException.class,
                () -> SocketPermissions.requireOwnerOnlyDirectory(dir.resolve("agent-connector-kernel.sock")));
        assertTrue(e.getMessage().contains("rwxr-xr-x"), e.getMessage());
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)), "not changed");
    }

    @Test
    void theSocketIsMadeTheOwnersAlone(@TempDir Path root) throws IOException {
        Path dir = root.resolve("sockets");
        Path socket = dir.resolve("agent-connector-kernel.sock");
        SocketPermissions.requireOwnerOnlyDirectory(socket);
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            SocketPermissions.restrictToOwner(socket);
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)));
        }
    }
}
