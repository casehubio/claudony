package dev.remotecc.server.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.remotecc.config.RemoteCCConfig;
import io.quarkus.security.webauthn.WebAuthnUserProvider;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.ext.auth.webauthn.Authenticator;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class CredentialStore implements WebAuthnUserProvider {

    // Note: publicKeyAlgorithm is intentionally omitted — io.vertx.ext.auth.webauthn.Authenticator
    // exposes no getter or setter for the algorithm field, so it cannot be persisted or restored.
    record StoredCredential(
        String username,
        String credentialId,
        String publicKey,
        long counter,
        String aaguid
    ) {}

    private static final TypeReference<List<StoredCredential>> LIST_TYPE =
        new TypeReference<>() {};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RemoteCCConfig config;

    public CredentialStore(RemoteCCConfig config) {
        this.config = config;
    }

    private Path credentialsPath() {
        return Path.of(config.credentialsFile());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean isEmpty() {
        return load().isEmpty();
    }

    @Override
    public Uni<List<Authenticator>> findWebAuthnCredentialsByUserName(String userName) {
        return Uni.createFrom()
            .item(() -> load().stream()
                .filter(c -> c.username().equals(userName))
                .map(CredentialStore::toAuthenticator)
                .collect(Collectors.toList()))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<List<Authenticator>> findWebAuthnCredentialsByCredID(String credId) {
        return Uni.createFrom()
            .item(() -> load().stream()
                .filter(c -> c.credentialId().equals(credId))
                .map(CredentialStore::toAuthenticator)
                .collect(Collectors.toList()))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> updateOrStoreWebAuthnCredentials(Authenticator authenticator) {
        return Uni.createFrom()
            .<Void>item(() -> {
                storeOrUpdate(authenticator);
                return null;
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    // -------------------------------------------------------------------------
    // Package-private test helpers
    // -------------------------------------------------------------------------

    void writeForTest(String username, String credId, String pubKey,
                      long counter, String aaguid) {
        synchronized (this) {
            var creds = new ArrayList<>(load());
            creds.add(new StoredCredential(username, credId, pubKey, counter, aaguid));
            save(creds);
        }
    }

    void updateCounter(String credId, long newCounter) {
        synchronized (this) {
            var creds = load().stream()
                .map(c -> c.credentialId().equals(credId)
                    ? new StoredCredential(c.username(), c.credentialId(), c.publicKey(),
                                          newCounter, c.aaguid())
                    : c)
                .collect(Collectors.toCollection(ArrayList::new));
            save(creds);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void storeOrUpdate(Authenticator authenticator) {
        synchronized (this) {
            var existing = load();
            var credId = authenticator.getCredID();
            boolean found = false;
            var updated = new ArrayList<StoredCredential>();
            for (var c : existing) {
                if (c.credentialId().equals(credId)) {
                    updated.add(new StoredCredential(
                        c.username(), c.credentialId(), c.publicKey(),
                        authenticator.getCounter(), c.aaguid()));
                    found = true;
                } else {
                    updated.add(c);
                }
            }
            if (!found) {
                updated.add(new StoredCredential(
                    authenticator.getUserName(),
                    credId,
                    authenticator.getPublicKey(),
                    authenticator.getCounter(),
                    authenticator.getAaguid()
                ));
            }
            save(updated);
        }
    }

    private synchronized List<StoredCredential> load() {
        var path = credentialsPath();
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(path.toFile(), LIST_TYPE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read credentials file: " + path, e);
        }
    }

    private void save(List<StoredCredential> creds) {
        var path = credentialsPath();
        var tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MAPPER.writeValue(tmp.toFile(), creds);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (e.g., Windows) — skip
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write credentials file: " + path, e);
        }
    }

    private static Authenticator toAuthenticator(StoredCredential c) {
        return new Authenticator()
            .setUserName(c.username())
            .setCredID(c.credentialId())
            .setPublicKey(c.publicKey())
            .setCounter(c.counter())
            .setAaguid(c.aaguid());
    }
}
