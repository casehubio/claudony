package dev.remotecc.server.auth;

import dev.remotecc.config.RemoteCCConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ApiKeyService {

    private static final Logger LOG = Logger.getLogger(ApiKeyService.class);

    @Inject
    RemoteCCConfig config;

    private Optional<String> resolvedKey = Optional.empty();

    // For direct construction in tests — matches CredentialStore pattern
    ApiKeyService(RemoteCCConfig config) {
        this.config = config;
    }

    // CDI no-arg constructor
    ApiKeyService() {}

    // TODO: Alternative provisioning — env var in launchd plist (REMOTECC_AGENT_API_KEY):
    //       no file needed, suitable for production macOS service deployment.
    // TODO: Alternative provisioning — interactive stdin prompt on first run:
    //       ask user to enter a key or press Enter to generate; useful for headless setup scripts.

    /** Called by ServerStartup after ensureDirectories(). Generates key if absent. */
    public void initServer() {
        resolvedKey = loadFromConfig();
        if (resolvedKey.isPresent()) return;

        resolvedKey = loadFromFile();
        if (resolvedKey.isPresent()) return;

        var key = "remotecc-" + UUID.randomUUID().toString().replace("-", "");
        if (persistKey(key)) {
            resolvedKey = Optional.of(key);
            logGenerationBanner(key);
        }
        // If persistKey failed, it already logged ERROR — resolvedKey stays empty
    }

    /** Called by AgentStartup before checkServerConnectivity(). Warns if key absent. */
    public void initAgent() {
        resolvedKey = loadFromConfig();
        if (resolvedKey.isPresent()) return;

        resolvedKey = loadFromFile();
        if (resolvedKey.isPresent()) return;

        logMissingKeyWarning();
    }

    /** Returns the resolved key after init. Empty if agent started without a key. */
    public Optional<String> getKey() {
        return resolvedKey;
    }

    private Optional<String> loadFromConfig() {
        return config.agentApiKey().filter(k -> !k.isBlank());
    }

    private Optional<String> loadFromFile() {
        var keyFile = keyFilePath();
        if (!Files.exists(keyFile)) return Optional.empty();
        try {
            var key = Files.readString(keyFile).strip();
            return key.isBlank() ? Optional.empty() : Optional.of(key);
        } catch (IOException e) {
            LOG.warnf("Could not read API key file %s: %s", keyFile, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean persistKey(String key) {
        var keyFile = keyFilePath();
        try {
            Files.writeString(keyFile, key);
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem — skip chmod
            }
            return true;
        } catch (IOException e) {
            LOG.errorf("Failed to write API key file %s: %s", keyFile, e.getMessage());
            return false;
        }
    }

    private Path keyFilePath() {
        return Path.of(config.credentialsFile()).getParent().resolve("api-key");
    }

    private void logGenerationBanner(String key) {
        var keyFile = keyFilePath().toAbsolutePath();
        LOG.warn("\n================================================================\n" +
                "REMOTECC — API Key Generated (first run)\n" +
                "  Key:      " + key + "\n" +
                "  Saved to: " + keyFile + "\n\n" +
                "  Same machine (agent + server co-located): no action needed.\n" +
                "  Different machine: configure the agent with —\n" +
                "    export REMOTECC_AGENT_API_KEY=" + key + "\n" +
                "  or in agent config:\n" +
                "    remotecc.agent.api-key=" + key + "\n" +
                "================================================================");
    }

    private void logMissingKeyWarning() {
        LOG.warn("\n================================================================\n" +
                "REMOTECC AGENT — No API Key Configured\n" +
                "  MCP tools will return 401 until a key is set.\n" +
                "  Copy the key from the server's ~/.remotecc/api-key and set:\n" +
                "    export REMOTECC_AGENT_API_KEY=<key>\n" +
                "  or in agent config:\n" +
                "    remotecc.agent.api-key=<key>\n" +
                "================================================================");
    }
}
