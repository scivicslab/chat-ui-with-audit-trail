package com.scivicslab.chatui.kernel;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.DomainSocketServerStart;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Keeps the Quarkus HTTP server's Unix domain socket its owner's alone, when the application listens on one
 * ({@code quarkus.http.domain-socket-enabled}; https://quarkus.io/guides/vertx-reference#use-a-unix-domain-socket).
 * The MCP server and the LLM proxy both answer on it (tasks/todo.md, Decisions 10, 12 and 13, step 3).
 *
 * <ul>
 * <li>A domain socket needs Vert.x's native transport: the epoll dependency, which this module brings, and
 * {@code quarkus.vertx.prefer-native-transport=true}, which the application sets together with
 * {@code quarkus.http.domain-socket-enabled} (https://quarkus.io/guides/vertx-reference, "Native transports"). Without
 * it the server cannot bind the socket, so start-up stops here first with that reason.</li>
 * <li>At start-up the socket's directory must be owner-only. If it is not, start-up fails: a socket others can reach
 * would take any local process's requests. The launcher creates the directory 0700 before the application starts,
 * since the server binds the socket into it.</li>
 * <li>When the domain-socket server has started ({@code DomainSocketServerStart}, https://quarkus.io/guides/http-reference),
 * the socket is made 0600.</li>
 * </ul>
 */
@ApplicationScoped
public class DomainSocketGuard {

    private static final Logger LOG = Logger.getLogger(DomainSocketGuard.class.getName());

    @ConfigProperty(name = "quarkus.http.domain-socket-enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "quarkus.http.domain-socket", defaultValue = "/var/run/io.quarkus.app.socket")
    String socket;

    @ConfigProperty(name = "quarkus.vertx.prefer-native-transport", defaultValue = "false")
    boolean nativeTransport;

    void checkDirectory(@Observes StartupEvent event) {
        if (!enabled) return;
        if (!nativeTransport) {
            throw new IllegalStateException("quarkus.http.domain-socket-enabled needs quarkus.vertx.prefer-native-transport=true");
        }
        try {
            SocketPermissions.requireOwnerOnlyDirectory(Path.of(socket));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void restrictSocket(@ObservesAsync DomainSocketServerStart start) {
        try {
            SocketPermissions.restrictToOwner(Path.of(socket));
            LOG.info("the domain socket " + socket + " is its owner's alone (0600)");
        } catch (IOException e) {
            LOG.severe("could not make the domain socket " + socket + " 0600: " + e);
        }
    }
}
