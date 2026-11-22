package com.scivicslab.chatui.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * The owner-only rule for agent-connector-kernel's Unix domain socket (tasks/todo.md, Decisions 10 and 12): a 0600
 * socket in a 0700 directory. A directory that exists with wider permissions is refused rather than changed.
 */
public final class SocketPermissions {

    private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");

    private SocketPermissions() {
    }

    /**
     * Creates the socket's directory owner-only when it is missing, and checks it otherwise.
     *
     * @param socket the socket file
     * @throws IOException when the directory exists and others may read or enter it
     */
    public static void requireOwnerOnlyDirectory(Path socket) throws IOException {
        Path dir = socket.toAbsolutePath().getParent();
        if (Files.notExists(dir)) {
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, OWNER_ONLY_DIR);
        }
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir);
        if (!OWNER_ONLY_DIR.containsAll(perms)) {
            throw new IOException("the socket directory " + dir + " must be its owner's alone, not "
                    + PosixFilePermissions.toString(perms));
        }
    }

    /** Makes the socket file its owner's alone (0600). */
    public static void restrictToOwner(Path socket) throws IOException {
        Files.setPosixFilePermissions(socket, OWNER_ONLY_FILE);
    }
}
