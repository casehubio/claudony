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
        if (identity.isAnonymous()) return Uni.createFrom().item(identity);
        var username = identity.getPrincipal().getName();
        return credentialStore.findByUsername(username)
            .onItem().transform(records -> {
                if (records == null || records.isEmpty()) return identity;
                var stored = credentialStore.loadForTest();
                var match = stored.stream()
                    .filter(c -> c.username().equals(username))
                    .findFirst()
                    .orElse(null);
                if (match == null || match.roles().isEmpty()) return identity;
                var builder = QuarkusSecurityIdentity.builder(identity);
                match.roles().forEach(builder::addRole);
                return builder.build();
            });
    }
}
