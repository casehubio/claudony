package io.casehub.claudony.server.auth;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class CredentialRoleAugmentor implements SecurityIdentityAugmentor {

    @Inject CredentialStore credentialStore;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {return Uni.createFrom().item(identity);}
        var username = identity.getPrincipal().getName();
        return Uni.createFrom().item(() -> credentialStore.findRolesByUsername(username))
                  .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                  .onItem().transform(roles -> {
                    if (roles.isEmpty()) {return identity;}
                    var builder = QuarkusSecurityIdentity.builder(identity);
                    roles.forEach(builder::addRole);
                    return builder.build();
                });
    }
}
