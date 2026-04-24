package dev.claudony.server.auth;

import dev.claudony.config.ClaudonyConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@ApplicationScoped
public class FleetKeyService {

    private static final Logger LOG = Logger.getLogger(FleetKeyService.class);

    @Inject
    ClaudonyConfig config;

    private final Path keyFile;
    private volatile Optional<String> key = Optional.empty();

    /** CDI no-arg constructor. */
    FleetKeyService() {
        this.keyFile = Path.of(System.getProperty("user.home"), ".claudony", "fleet-key");
    }

    /** Package-private constructor for unit tests — injects temp key file path. */
    FleetKeyService(ClaudonyConfig config, Path keyFile) {
        this.config = config;
        this.keyFile = keyFile;
    }

    @PostConstruct
    void init() {
        key = config.fleetKey().filter(k -> !k.isBlank());
        if (key.isPresent()) return;

        key = loadFromFile();
        if (key.isEmpty()) {
            LOG.warn("claudony.fleet-key not configured — peer-to-peer calls will be" +
                    " rejected by authenticated peers. Generate with POST /api/peers/generate-fleet-key");
        }
    }

    public Optional<String> getKey() {
        return key;
    }

    /**
     * Generates a new random fleet key, persists it to disk, updates in-memory key.
     * Called by POST /api/peers/generate-fleet-key.
     */
    public String generateAndSave() throws IOException {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        var newKey = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Files.createDirectories(keyFile.getParent());
        Files.writeString(keyFile, newKey);
        try {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem — skip chmod
        }

        key = Optional.of(newKey);
        LOG.infof("Generated new fleet key — saved to %s. Copy to all fleet members via CLAUDONY_FLEET_KEY.", keyFile);
        return newKey;
    }

    private Optional<String> loadFromFile() {
        if (!Files.exists(keyFile)) return Optional.empty();
        try {
            var k = Files.readString(keyFile).strip();
            return k.isBlank() ? Optional.empty() : Optional.of(k);
        } catch (IOException e) {
            LOG.warnf("Could not read fleet key from %s: %s", keyFile, e.getMessage());
            return Optional.empty();
        }
    }
}
