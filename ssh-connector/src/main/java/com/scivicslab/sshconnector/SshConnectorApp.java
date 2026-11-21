package com.scivicslab.sshconnector;

import com.scivicslab.chatui.confine.PodmanHarness;
import com.scivicslab.chatui.harness.HarnessLaunch;
import com.scivicslab.chatui.llm.LlmProxyRoutes;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.logging.Logger;

/**
 * What ssh-connector does once Quarkus is up (tasks/todo.md, stage 11): the MCP server, with the ssh tools, and the LLM
 * proxy are on the domain socket; it opens the harness's scope to the proxy, writes the harness's configuration, starts
 * the harness confined in Podman and ends with the harness's exit status.
 *
 * <p>With {@code ssh-connector.harness.kind=none} it starts no harness. It writes the scope's MCP key to
 * {@code private/mcp-key} (0600), for an MCP client the user starts, and serves until it is stopped.</p>
 */
public class SshConnectorApp implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(SshConnectorApp.class.getName());

    @Inject
    ConnectorSettings settings;

    @Inject
    ConnectorScope scope;

    @Inject
    LlmProxyRoutes proxy;

    @Override
    public int run(String... args) throws Exception {
        if (settings.podmanHarness() == null) {
            writeOwnerOnly(settings.privateDir(), "mcp-key", scope.key() + "\n");
            LOG.info("ssh-connector serves on " + settings.socket() + " with no harness");
            Quarkus.waitForExit();
            return 0;
        }
        PodmanHarness harness = settings.podmanHarness();
        String llmKey = proxy.register(scope.name());
        Path config = writeOwnerOnly(settings.harnessDir(),
                Path.of(harness.containerConfigPath()).getFileName().toString(),
                harness.configText(scope.name(), settings.model()));
        HarnessLaunch launch = ConnectorLaunch.launch(settings, config, scope.key(), llmKey, List.of(args),
                System.console() != null);
        LOG.info("starting " + settings.harness() + ": " + String.join(" ", launch.command()));
        Process process = launch.processBuilder().inheritIO().start();
        int status = process.waitFor();
        LOG.info(settings.harness() + " ended with " + status);
        proxy.unregister(scope.name());
        return status;
    }

    /** Writes {@code name} in {@code dir} (made 0700 when missing) as a 0600 file, replacing it whole. */
    static Path writeOwnerOnly(Path dir, String name, String text) throws IOException {
        if (Files.notExists(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        Path tmp = Files.createTempFile(dir, "." + name + ".", ".tmp");
        Files.writeString(tmp, text);
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        Path file = dir.resolve(name);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return file;
    }
}
