package dev.remotecc.server.auth;

import dev.remotecc.config.RemoteCCConfig;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class ApiKeyAuthMechanism implements HttpAuthenticationMechanism {

    @Inject
    RemoteCCConfig config;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        var apiKey = context.request().getHeader("X-Api-Key");
        if (apiKey == null) {
            // No key provided — defer to path policy (allow anonymous for non-protected paths)
            return Uni.createFrom().optional(Optional.empty());
        }
        var expected = config.agentApiKey();
        if (expected.isEmpty() || !apiKey.equals(expected.get())) {
            return Uni.createFrom().failure(
                new io.quarkus.security.AuthenticationFailedException("Invalid API key"));
        }
        return Uni.createFrom().item(
            QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("agent"))
                .addRole("user")
                .build());
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(
            new ChallengeData(HttpResponseStatus.UNAUTHORIZED.code(), "WWW-Authenticate", "ApiKey"));
    }

    @Override
    public Uni<Boolean> sendChallenge(RoutingContext context) {
        var path = context.request().path();
        // Only intercept challenge for API and WebSocket paths to prevent WebAuthn redirect
        if (path.startsWith("/api/") || path.startsWith("/ws/")) {
            context.response().setStatusCode(HttpResponseStatus.UNAUTHORIZED.code());
            context.response().end();
            return Uni.createFrom().item(Boolean.TRUE);
        }
        return Uni.createFrom().item(Boolean.FALSE);
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of();
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().optional(Optional.empty());
    }

    @Override
    public int getPriority() {
        return 2000;
    }
}
