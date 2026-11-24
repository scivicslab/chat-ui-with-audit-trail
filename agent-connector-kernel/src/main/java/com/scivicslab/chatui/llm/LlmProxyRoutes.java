package com.scivicslab.chatui.llm;

import com.scivicslab.pojoactor.core.ActorSystem;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Mounts agent-connector-kernel's LLM proxy on the application's Quarkus router at {@value LlmProxy#PREFIX}, when the
 * application names an upstream (tasks/todo.md, step 4a.2; https://quarkus.io/guides/reactive-routes, "Using the Vert.x
 * Web Router"). chat-ui names none, so it has no proxy. A launcher opens a scope with {@link #register}.
 *
 * <ul>
 * <li>{@code agent-connector-kernel.llm.upstream}: the provider's base URL;</li>
 * <li>{@code agent-connector-kernel.llm.credential-header}: where the provider reads the credential, by default
 * {@code authorization};</li>
 * <li>{@code agent-connector-kernel.llm.credential}: the real credential, never handed to a harness.</li>
 * </ul>
 */
@ApplicationScoped
public class LlmProxyRoutes {

    private static final Logger LOG = Logger.getLogger(LlmProxyRoutes.class.getName());

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "agent-connector-kernel.llm.upstream")
    Optional<URI> upstream;

    @ConfigProperty(name = "agent-connector-kernel.llm.credential-header", defaultValue = "authorization")
    String credentialHeader;

    // Optional: an empty default would count as missing to MicroProfile Config and stop the application's start-up.
    @ConfigProperty(name = "agent-connector-kernel.llm.credential")
    Optional<String> credential;

    private ActorSystem system;
    private volatile LlmProxy proxy;

    void mount(@Observes Router router) {
        if (upstream.isEmpty()) return;
        system = new ActorSystem("agent-connector-kernel-llm-proxy");
        proxy = new LlmProxy(vertx, system, upstream.get(), credentialHeader, credential.orElse(""));
        proxy.mount(router);
        LOG.info("LLM proxy at " + LlmProxy.PREFIX + " in front of " + upstream.get());
    }

    /** @return the throw-away key of the newly opened scope; the proxy must be mounted */
    public String register(String scope) {
        LlmProxy p = proxy;
        if (p == null) throw new IllegalStateException("no LLM proxy: agent-connector-kernel.llm.upstream is not set");
        return p.register(scope);
    }

    /** Closes a scope. */
    public void unregister(String scope) {
        LlmProxy p = proxy;
        if (p != null) p.unregister(scope);
    }

    @PreDestroy
    void close() {
        if (proxy != null) proxy.close();
        if (system != null) system.terminate();
    }
}
