package com.scivicslab.ack.confinementhost;

import com.scivicslab.chatui.llm.LlmProxyRoutes;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * At start-up, opens the scope to the LLM proxy and writes both keys to {@code keys} in the host's owner-only directory
 * (0600), as a launcher would hand them to podman's environment. A check reads them from there.
 */
@ApplicationScoped
public class KeysFile {

    @Inject
    HostScopes scopes;

    @Inject
    LlmProxyRoutes proxy;

    @ConfigProperty(name = "confinement-host.dir")
    String dir;

    void write(@Observes StartupEvent event) throws IOException {
        String llmKey = proxy.register(scopes.scope());
        Path tmp = Path.of(dir, "keys.tmp");
        Files.writeString(tmp, "mcp=" + scopes.key() + "\nllm=" + llmKey + "\n");
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        Files.move(tmp, Path.of(dir, "keys"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
