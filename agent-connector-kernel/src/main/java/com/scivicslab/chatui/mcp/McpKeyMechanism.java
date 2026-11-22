package com.scivicslab.chatui.mcp;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Set;

/**
 * The key check of agent-connector-kernel's MCP server on the Quarkus security layer (tasks/todo.md, Decisions 12 and
 * 13; https://quarkus.io/guides/security-customization). It answers only for the MCP server's path; every other path is
 * left to the application. The permission {@code agent-connector-kernel-mcp} in this module's
 * {@code META-INF/microprofile-config.properties} makes the path need an authenticated request, so a refused request
 * gets an HTTP status, not an MCP error (https://docs.quarkiverse.io/quarkus-mcp-server/2.0.x/reference-security.html).
 *
 * <p>The decision is {@link McpAccess}'s. A refused request goes on with no identity, and the permission refuses it
 * with the challenge below: an empty body, 400 when it names no scope and a key is required, otherwise 401. So an
 * unknown scope and a wrong key get the same answer.</p>
 */
@ApplicationScoped
public class McpKeyMechanism implements HttpAuthenticationMechanism {

    private static final String REFUSED = McpKeyMechanism.class.getName() + ".refused";

    @Inject
    McpScopes scopes;

    @ConfigProperty(name = "agent-connector-kernel.mcp.require-key", defaultValue = "true")
    boolean requireKey;

    @ConfigProperty(name = "agent-connector-kernel.mcp.scope-header", defaultValue = McpAccess.DEFAULT_SCOPE_HEADER)
    String scopeHeader;

    @ConfigProperty(name = "quarkus.mcp.server.http.root-path", defaultValue = "/mcp")
    String rootPath;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        if (!isMcpPath(context.normalizedPath())) return Uni.createFrom().nullItem();
        McpAccess.Decision decision = new McpAccess(requireKey, scopes::opens)
                .decide(context.request().getHeader(scopeHeader), context.request().getHeader("Authorization"));
        if (decision instanceof McpAccess.Refuse refuse) {
            // No identity: the permission on the MCP path then refuses the request with getChallenge below. Failing
            // here instead would reach quarkus-mcp-server, which answers any authentication failure with 401 and an
            // MCP error naming the exception, and so could not give 400.
            context.put(REFUSED, refuse.status());
            return Uni.createFrom().nullItem();
        }
        String scope = ((McpAccess.Allow) decision).scope();
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(scope == null ? "" : scope)).build());
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        Integer status = context.get(REFUSED);
        return Uni.createFrom().item(new ChallengeData(status == null ? 401 : status, null, null));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of();
    }

    private boolean isMcpPath(String path) {
        String root = rootPath.endsWith("/") ? rootPath.substring(0, rootPath.length() - 1) : rootPath;
        return path != null && (path.equals(root) || path.startsWith(root + "/"));
    }
}
