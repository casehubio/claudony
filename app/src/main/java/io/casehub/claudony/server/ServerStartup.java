package io.casehub.claudony.server;

import io.casehub.claudony.config.ClaudonyConfig;
import io.casehub.claudony.server.auth.ApiKeyService;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ServerStartup {

    private static final Logger LOG = Logger.getLogger(ServerStartup.class);

    @Inject ClaudonyConfig         config;
    @Inject TmuxService            tmux;
    @Inject SessionRegistry        registry;
    @Inject ApiKeyService          apiKeyService;
    @Inject ChannelGateway         gateway;
    @Inject ClaudonyChannelBackend channelBackend;
    @Inject QhorusDashboardService dashboard;

    void onStart(@Observes StartupEvent event) {
        if (!config.isServerMode()) return;
        checkTmux();
        ensureDirectories();
        apiKeyService.initServer();
        bootstrapRegistry();
        bootstrapChannelBackends();
        LOG.infof("Claudony Server ready — http://%s:%d", config.bind(), config.port());
    }

    private void ensureDirectories() {
        // ~/.claudony  — config/credentials (hidden, system)
        // ~/claudony-workspace — default session working directory (visible, user-facing)
        for (var dir : new String[]{
                Path.of(config.credentialsFile()).getParent().toString(),
                config.defaultWorkingDir()}) {
            try {
                Files.createDirectories(Path.of(dir));
                LOG.debugf("Directory ready: %s", dir);
            } catch (IOException e) {
                LOG.warnf("Could not create directory %s: %s", dir, e.getMessage());
            }
        }
    }

    private void checkTmux() {
        try {
            var version = tmux.tmuxVersion();
            LOG.infof("tmux found: %s", version);
        } catch (IOException | InterruptedException e) {
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
                        SessionStatus.IDLE, now, now, Optional.empty(), Optional.empty(), Optional.empty()));
                count++;
            }
            LOG.infof("Bootstrapped %d existing session(s) from tmux", count);
        } catch (IOException | InterruptedException e) {
            LOG.warn("Could not bootstrap from tmux list-sessions: " + e.getMessage());
        }
    }

    void bootstrapChannelBackends() {
        Set<String> casePrefixes = registry.all().stream()
                .flatMap(s -> s.caseId().stream())
                .map(caseId -> "case-" + caseId + "/")
                .collect(Collectors.toSet());

        if (casePrefixes.isEmpty()) return;

        try {
            dashboard.listChannels().await().indefinitely().stream()
                    .filter(ch -> casePrefixes.stream().anyMatch(p -> ch.name().startsWith(p)))
                    .forEach(ch -> {
                        ChannelRef ref = new ChannelRef(ch.channelId(), ch.name());
                        gateway.deregisterBackend(ch.channelId(), ClaudonyChannelBackend.BACKEND_ID);
                        channelBackend.open(ref, Map.of());
                        gateway.registerBackend(ch.channelId(), channelBackend, "human_observer");
                    });
            LOG.infof("Re-registered ClaudonyChannelBackend for %d case prefix(es)", casePrefixes.size());
        } catch (Exception e) {
            LOG.warn("Could not re-register channel backends on startup: " + e.getMessage());
        }
    }
}
