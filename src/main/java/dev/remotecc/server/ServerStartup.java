package dev.remotecc.server;

import dev.remotecc.config.RemoteCCConfig;
import dev.remotecc.server.auth.ApiKeyService;
import dev.remotecc.server.model.Session;
import dev.remotecc.server.model.SessionStatus;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class ServerStartup {

    private static final Logger LOG = Logger.getLogger(ServerStartup.class);

    @Inject RemoteCCConfig config;
    @Inject TmuxService tmux;
    @Inject SessionRegistry registry;
    @Inject ApiKeyService apiKeyService;

    void onStart(@Observes StartupEvent event) {
        if (!config.isServerMode()) return;
        checkTmux();
        ensureDirectories();
        apiKeyService.initServer();
        bootstrapRegistry();
        LOG.infof("RemoteCC Server ready — http://%s:%d", config.bind(), config.port());
    }

    private void ensureDirectories() {
        // ~/.remotecc  — config/credentials (hidden, system)
        // ~/remotecc-workspace — default session working directory (visible, user-facing)
        for (var dir : new String[]{
                Path.of(config.credentialsFile()).getParent().toString(),
                config.defaultWorkingDir()}) {
            try {
                Files.createDirectories(Path.of(dir));
                LOG.debugf("Directory ready: %s", dir);
            } catch (Exception e) {
                LOG.warnf("Could not create directory %s: %s", dir, e.getMessage());
            }
        }
    }

    private void checkTmux() {
        try {
            var version = tmux.tmuxVersion();
            LOG.infof("tmux found: %s", version);
        } catch (Exception e) {
            throw new IllegalStateException(
                "tmux not found on PATH. Install with: brew install tmux", e);
        }
    }

    void bootstrapRegistry() {
        try {
            var names = tmux.listSessionNames();
            var prefix = config.tmuxPrefix();
            int count = 0;
            for (var name : names) {
                if (!name.startsWith(prefix)) continue;
                var now = Instant.now();
                registry.register(new Session(
                        UUID.randomUUID().toString(), name,
                        "unknown", config.claudeCommand(),
                        SessionStatus.IDLE, now, now));
                count++;
            }
            LOG.infof("Bootstrapped %d existing session(s) from tmux", count);
        } catch (Exception e) {
            LOG.warn("Could not bootstrap from tmux list-sessions: " + e.getMessage());
        }
    }
}
