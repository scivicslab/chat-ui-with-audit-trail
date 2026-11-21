package com.scivicslab.sshconnector;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * ssh-connector's entry point (tasks/todo.md, stage 11). It finds ssh-connector's directory, hands Quarkus its one
 * settings file and the place of the domain socket and the log, and starts the application. The arguments are the
 * harness's own, after its command, e.g. {@code run "a prompt"} for OpenCode.
 *
 * <p>The directory is {@code -Dssh-connector.dir}, else {@code $SSH_CONNECTOR_DIR}, else {@code ~/.ssh-connector}.
 * It must exist, belong to its owner alone (0700) and hold {@value ConnectorSettings#FILE}. A system property given on
 * the command line wins over the file, as in any Quarkus application.</p>
 */
@QuarkusMain
public final class SshConnectorMain {

    private SshConnectorMain() {
    }

    public static void main(String... args) {
        Path dir = directory();
        try {
            String perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS));
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !perms.equals("rwx------")) {
                fail(dir + " must be a directory of its owner alone (rwx------), not " + perms);
            }
        } catch (IOException e) {
            fail("ssh-connector's directory " + dir + " cannot be read: " + e.getMessage());
        }
        Path file = dir.resolve(ConnectorSettings.FILE);
        if (!Files.isRegularFile(file)) fail("the settings file " + file + " does not exist");
        setIfAbsent("ssh-connector.dir", dir.toString());
        setIfAbsent("quarkus.config.locations", file.toUri().toString());
        setIfAbsent("quarkus.http.domain-socket", dir.resolve("sockets").resolve(
                com.scivicslab.chatui.confine.PodmanConfinement.SOCKET).toString());
        setIfAbsent("quarkus.log.file.path", dir.resolve("ssh-connector.log").toString());
        Quarkus.run(SshConnectorApp.class, args);
    }

    private static Path directory() {
        String value = System.getProperty("ssh-connector.dir");
        if (value == null || value.isBlank()) value = System.getenv("SSH_CONNECTOR_DIR");
        if (value == null || value.isBlank()) value = System.getProperty("user.home") + "/.ssh-connector";
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) System.setProperty(key, value);
    }

    private static void fail(String message) {
        System.err.println("ssh-connector: " + message);
        System.exit(2);
    }
}
