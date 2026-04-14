package dev.claudony.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * MicroProfile ConfigSource that provides a per-deployment session encryption key.
 *
 * <p>Ordinal 200 — sits below application.properties profile keys (250) so dev/test
 * fixed keys still win, but provides the only value in prod where the hardcoded
 * shared key has been removed.</p>
 *
 * <p>On first run, generates a 256-bit SecureRandom key, persists it to
 * ~/.claudony/encryption-key with rw------- permissions, and caches it in memory.
 * On subsequent runs, reads from disk. The env var
 * QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY (ordinal 300) always wins.</p>
 *
 * <p>getProperties() intentionally returns an empty map so the key value never
 * appears in config dumps, actuator endpoints, or log lines that enumerate
 * all config properties.</p>
 */
@RegisterForReflection
public class EncryptionKeyConfigSource implements ConfigSource {

    private static final Logger LOG = Logger.getLogger(EncryptionKeyConfigSource.class);
    private static final String KEY_PROPERTY = "quarkus.http.auth.session.encryption-key";
    private static final int ORDINAL = 200;

    private final Path configDir;
    private volatile String cachedKey;

    /** Called by ServiceLoader at Quarkus bootstrap. */
    public EncryptionKeyConfigSource() {
        this.configDir = Path.of(System.getProperty("user.home"), ".claudony");
    }

    /** Package-private — for unit tests only. Injects a temp directory. */
    EncryptionKeyConfigSource(Path configDir) {
        this.configDir = configDir;
    }

    @Override
    public String getValue(String propertyName) {
        if (!KEY_PROPERTY.equals(propertyName)) {
            return null;
        }
        return getOrGenerateKey();
    }

    @Override
    public String getName() {
        return "claudony-file-encryption-key";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    /**
     * Intentionally empty — prevents the encryption key from appearing in config
     * dumps, /q/config actuator responses, or any log that iterates all properties.
     * SmallRye Config resolves values via getValue(), not getProperties(), so
     * returning an empty map here does not affect key resolution.
     */
    @Override
    public Map<String, String> getProperties() {
        return Map.of();
    }

    @Override
    public Set<String> getPropertyNames() {
        return Set.of(KEY_PROPERTY);
    }

    // ─── Private implementation ──────────────────────────────────────────────

    private String getOrGenerateKey() {
        if (cachedKey != null) {
            return cachedKey;
        }
        synchronized (this) {
            if (cachedKey != null) {
                return cachedKey;
            }
            cachedKey = loadOrGenerate();
            return cachedKey;
        }
    }

    private String loadOrGenerate() {
        var keyFile = configDir.resolve("encryption-key");

        // Happy path: existing file from a previous run
        if (Files.exists(keyFile)) {
            try {
                var key = Files.readString(keyFile).strip();
                if (!key.isBlank()) {
                    LOG.debugf("Loaded session encryption key from %s", keyFile);
                    return key;
                }
            } catch (IOException e) {
                LOG.warnf("Could not read session encryption key from %s: %s — regenerating",
                        keyFile, e.getMessage());
            }
        }

        // First run — generate a new key
        var key = generateKey();

        // Ensure ~/.claudony/ exists
        if (!Files.exists(configDir)) {
            try {
                Files.createDirectories(configDir);
            } catch (IOException e) {
                LOG.warnf("Could not create config directory %s: %s" +
                        " — sessions will not survive restart", configDir, e.getMessage());
                return key;
            }
        }

        // Persist with owner-only permissions
        try {
            Files.writeString(keyFile, key);
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (Windows) — skip chmod, log a note
                LOG.info("Non-POSIX filesystem: session encryption key file permissions not restricted");
            }
            logGenerationBanner(keyFile);
        } catch (IOException e) {
            LOG.warnf("Could not persist session encryption key to %s: %s" +
                    " — sessions will not survive restart. Check directory permissions.", keyFile, e.getMessage());
        }

        return key;
    }

    private String generateKey() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void logGenerationBanner(Path keyFile) {
        LOG.info("\n================================================================\n" +
                "CLAUDONY — Session Encryption Key Generated (first run)\n" +
                "  Saved to: " + keyFile.toAbsolutePath() + "\n" +
                "  Permissions: rw------- (owner read/write only)\n\n" +
                "  Sessions will now survive server restarts.\n\n" +
                "  To use a custom key (e.g. from a secrets manager):\n" +
                "    export QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY=<your-key>\n" +
                "  The env var takes precedence over the persisted file.\n" +
                "================================================================");
    }
}
