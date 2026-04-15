package dev.claudony.server.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.claudony.config.ClaudonyConfig;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import io.quarkus.security.webauthn.WebAuthnUserProvider;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class CredentialStore implements WebAuthnUserProvider {

    // Persisted format. publicKey is Base64-encoded COSE key bytes; aaguid is UUID string.
    record StoredCredential(
        String username,
        String credentialId,
        String aaguid,
        String publicKey,
        long publicKeyAlgorithm,
        long counter
    ) {}

    private static final TypeReference<List<StoredCredential>> LIST_TYPE =
        new TypeReference<>() {};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClaudonyConfig config;

    public CredentialStore(ClaudonyConfig config) {
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
    public Uni<List<WebAuthnCredentialRecord>> findByUsername(String username) {
        return Uni.createFrom()
            .item(() -> load().stream()
                .filter(c -> c.username().equals(username))
                .map(CredentialStore::toRecord)
                .collect(Collectors.toList()))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<WebAuthnCredentialRecord> findByCredentialId(String credentialId) {
        return Uni.createFrom()
            .item(() -> load().stream()
                .filter(c -> c.credentialId().equals(credentialId))
                .map(CredentialStore::toRecord)
                .findFirst()
                .orElse(null))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> store(WebAuthnCredentialRecord record) {
        return Uni.createFrom()
            .<Void>item(() -> {
                doStore(record);
                return null;
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> update(String credentialId, long counter) {
        return Uni.createFrom()
            .<Void>item(() -> {
                doUpdate(credentialId, counter);
                return null;
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    // -------------------------------------------------------------------------
    // Package-private test helpers
    // -------------------------------------------------------------------------

    void writeForTest(String username, String credentialId, String publicKey,
                      long counter, String aaguid) {
        UUID parsedAaguid;
        try {
            parsedAaguid = UUID.fromString(aaguid);
        } catch (IllegalArgumentException e) {
            parsedAaguid = new UUID(0, 0);
        }
        byte[] pubKeyBytes = publicKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        synchronized (this) {
            var creds = new ArrayList<>(load());
            creds.add(new StoredCredential(
                username, credentialId, parsedAaguid.toString(),
                Base64.getEncoder().encodeToString(pubKeyBytes), -7L, counter));
            save(creds);
        }
    }

    void updateCounter(String credId, long newCounter) {
        doUpdate(credId, newCounter);
    }

    List<StoredCredential> loadForTest() {
        return load();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void doStore(WebAuthnCredentialRecord record) {
        var data = record.getRequiredPersistedData();
        synchronized (this) {
            var creds = new ArrayList<>(load());
            creds.removeIf(c -> c.credentialId().equals(data.credentialId()));
            creds.add(new StoredCredential(
                data.username(),
                data.credentialId(),
                data.aaguid().toString(),
                Base64.getEncoder().encodeToString(data.publicKey()),
                data.publicKeyAlgorithm(),
                data.counter()
            ));
            save(creds);
        }
    }

    private void doUpdate(String credentialId, long newCounter) {
        synchronized (this) {
            var creds = load().stream()
                .map(c -> c.credentialId().equals(credentialId)
                    ? new StoredCredential(c.username(), c.credentialId(), c.aaguid(),
                                          c.publicKey(), c.publicKeyAlgorithm(), newCounter)
                    : c)
                .collect(Collectors.toCollection(ArrayList::new));
            save(creds);
        }
    }

    private static WebAuthnCredentialRecord toRecord(StoredCredential c) {
        return WebAuthnCredentialRecord.fromRequiredPersistedData(
            new WebAuthnCredentialRecord.RequiredPersistedData(
                c.username(),
                c.credentialId(),
                UUID.fromString(c.aaguid()),
                Base64.getDecoder().decode(c.publicKey()),
                c.publicKeyAlgorithm(),
                c.counter()
            )
        );
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
}
