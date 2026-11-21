package com.scivicslab.chatui.llm;

import com.scivicslab.llmrelay.RelayOptions;
import com.scivicslab.llmrelay.proxy.RelayProxy;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

import java.net.URI;
import java.util.List;

/**
 * agent-connector-kernel's LLM proxy, in front of one LLM provider ({@code LlmKeyInjectionProxy_261121_oo01};
 * tasks/todo.md, steps 4a and 4a.2). A harness gets {@code /llm/<scope>/v1} as its base URL and a throw-away key; the
 * proxy checks the key, puts the real credential in its place, and streams the answer back as it arrives.
 *
 * <p>Built on llm-relay-proxy, which is vertx-http-proxy: the proxy is mounted on a Vert.x router, in an application
 * Quarkus's own router, so it answers on the application's HTTP server and on its domain socket alike (Decision 13). The
 * decisions are {@link LlmProxyRequests}', run by {@link ScopeKeyInterceptor}. The throw-away keys live in a
 * {@link ProxyKeys} actor, asked without blocking the event loop.</p>
 */
public final class LlmProxy implements AutoCloseable {

    /** The path the proxy is mounted under. */
    public static final String PREFIX = "/llm/";

    private final ActorRef<ProxyKeys> keys;
    private final RelayProxy relay;

    /**
     * @param vertx            the Vert.x instance; in an application, Quarkus's own
     * @param system           the actor system the keys actor belongs to
     * @param upstream         the provider's base URL, e.g. {@code http://192.168.5.16:8000}
     * @param credentialHeader the header the provider reads the credential from, e.g. {@code authorization}
     * @param credential       the real credential, e.g. {@code Bearer ...}
     * @throws IllegalArgumentException for an https upstream: TLS to the upstream is not built yet (tasks/todo.md)
     */
    public LlmProxy(Vertx vertx, ActorSystem system, URI upstream, String credentialHeader, String credential) {
        if (!"http".equals(upstream.getScheme())) {
            throw new IllegalArgumentException("only an http upstream is supported for now, not " + upstream);
        }
        this.keys = system.actorOf("llm-proxy-keys-" + Integer.toHexString(System.identityHashCode(this)), new ProxyKeys());
        LlmProxyRequests requests = new LlmProxyRequests(
                scope -> keys.ask(k -> k.has(scope)).join(),
                (scope, key) -> keys.ask(k -> k.opens(scope, key)).join(),
                upstream, credentialHeader, credential);
        int port = upstream.getPort() < 0 ? 80 : upstream.getPort();
        this.relay = new RelayProxy(vertx, RelayOptions.DEFAULTS, upstream.getHost(), port,
                List.of(new ScopeKeyInterceptor(requests, credentialHeader)));
    }

    /** Mounts the proxy on {@code router} under {@value #PREFIX}. */
    public void mount(Router router) {
        relay.mount(router, PREFIX);
    }

    /**
     * Opens a scope to the proxy.
     *
     * @return the throw-away key the scope's harness is given
     */
    public String register(String scope) {
        return keys.ask(k -> k.register(scope)).join();
    }

    /** Closes a scope; its throw-away key no longer opens anything. */
    public void unregister(String scope) {
        keys.tell(k -> k.unregister(scope)).join();
    }

    /** Closes the keys actor. */
    @Override
    public void close() {
        keys.close();
    }
}
