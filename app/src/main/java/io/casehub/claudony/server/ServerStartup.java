package io.casehub.claudony.server;

import io.casehub.claudony.casehub.CaseHubConfig;
import io.casehub.claudony.casehub.ClaudonyWorkerExecutionManager;
import io.casehub.claudony.config.ClaudonyConfig;
import io.casehub.claudony.server.auth.ApiKeyService;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ServerStartup {

    private static final Logger LOG = Logger.getLogger(ServerStartup.class);

    @Inject ClaudonyConfig  config;
    @Inject CaseHubConfig  casehubConfig;
    @Inject TmuxService     tmux;
    @Inject SessionRegistry registry;
    @Inject ApiKeyService   apiKeyService;
    @Inject Instance<ClaudonyWorkerExecutionManager> workerExecManager;
    @Inject Instance<CrossTenantCaseInstanceRepository> caseInstanceRepo;

    void onStart(@Observes StartupEvent event) {
        if (!config.isServerMode()) return;
        checkTmux();
        ensureDirectories();
        apiKeyService.initServer();
        bootstrapRegistry();
        if (casehubConfig.enabled()) bootstrapCasehubWatchers();
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
                Optional<String> caseId = Optional.empty();
                Optional<String> roleName = Optional.empty();
                Optional<String> tenancyId = Optional.empty();
                try {
                    // Read casehub metadata from tmux session options (set during provision)
                    caseId = tmux.getSessionOption(name, "@casehub_case_id");
                    roleName = tmux.getSessionOption(name, "@casehub_role");
                    tenancyId = tmux.getSessionOption(name, "@casehub_tenant_id");
                } catch (IOException | InterruptedException e) {
                    // A single session option read failure must not abort the loop.
                    // Register without metadata — session is visible, but recovery watcher won't start.
                    LOG.warnf("Could not read casehub options for session %s — registering without metadata: %s",
                            name, e.getMessage());
                }
                registry.register(new Session(
                        UUID.randomUUID().toString(), name,
                        "unknown", config.claudeCommand(),
                        SessionStatus.IDLE, now, now, Optional.empty(), caseId, roleName,
                        tenancyId.orElse(TenancyConstants.DEFAULT_TENANT_ID)));
                count++;
            }
            LOG.infof("Bootstrapped %d existing session(s) from tmux", count);
        } catch (IOException | InterruptedException e) {
            LOG.warn("Could not bootstrap from tmux list-sessions: " + e.getMessage());
        }
    }

    void bootstrapCasehubWatchers() {
        if (caseInstanceRepo.isUnsatisfied()) {
            LOG.debug("CrossTenantCaseInstanceRepository not available — skipping casehub watcher recovery");
            return;
        }
        if (workerExecManager.isUnsatisfied()) {
            LOG.debug("ClaudonyWorkerExecutionManager not available — skipping casehub watcher recovery");
            return;
        }
        int started = new CasehubStartupService(
                        registry, caseInstanceRepo.get(), workerExecManager.get())
                .bootstrapWatchers();
        LOG.infof("Started %d casehub watcher(s) for recovered sessions", started);
    }

}
