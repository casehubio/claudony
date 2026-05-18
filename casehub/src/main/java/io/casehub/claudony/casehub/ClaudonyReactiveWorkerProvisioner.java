package io.casehub.claudony.casehub;

import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.api.model.Capability;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.Worker;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.ReactiveWorkerProvisioner;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ClaudonyReactiveWorkerProvisioner implements ReactiveWorkerProvisioner {

    static final String SESSION_PREFIX = "claudony-worker-";

    private final boolean enabled;
    private final TmuxService tmux;
    private final SessionRegistry registry;
    private final WorkerCommandResolver resolver;
    private final WorkerSessionMapping sessionMapping;
    private final String defaultWorkingDir;

    @Inject
    public ClaudonyReactiveWorkerProvisioner(
            CaseHubConfig config,
            TmuxService tmux,
            SessionRegistry registry,
            WorkerCommandResolver resolver,
            WorkerSessionMapping sessionMapping) {
        this(config.enabled(), tmux, registry, resolver, sessionMapping,
                config.workers().defaultWorkingDir());
    }

    ClaudonyReactiveWorkerProvisioner(boolean enabled, TmuxService tmux, SessionRegistry registry,
                                       WorkerCommandResolver resolver,
                                       WorkerSessionMapping sessionMapping,
                                       String defaultWorkingDir) {
        this.enabled = enabled;
        this.tmux = tmux;
        this.registry = registry;
        this.resolver = resolver;
        this.sessionMapping = sessionMapping;
        this.defaultWorkingDir = defaultWorkingDir;
    }

    @Override
    public Uni<Worker> provision(Set<String> capabilities, ProvisionContext context) {
        return Uni.createFrom()
                  .item(() -> doProvision(capabilities, context))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> terminate(String workerId) {
        return Uni.createFrom()
                  .<Void>item(() -> {
                      try {
                          tmux.killSession(SESSION_PREFIX + workerId);
                      } catch (IOException | InterruptedException e) {
                          // Session may already be gone — no-op
                      }
                      registry.remove(workerId);
                      return null;
                  })
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Set<String>> getCapabilities() {
        return Uni.createFrom().item(resolver.getAvailableCapabilities());
    }

    private Worker doProvision(Set<String> capabilities, ProvisionContext context) {
        if (!enabled) {
            throw new ProvisioningException(
                    "CaseHub integration is disabled — set claudony.casehub.enabled=true");
        }
        String sessionId = UUID.randomUUID().toString();
        String roleName = context.taskType() != null
                ? context.taskType()
                : capabilities.stream().findFirst().orElse("worker");
        String command = resolver.resolve(capabilities);
        String sessionName = SESSION_PREFIX + sessionId;

        try {
            tmux.createSession(sessionName, defaultWorkingDir, command);
        } catch (IOException | InterruptedException e) {
            throw new ProvisioningException("Failed to create tmux session for worker " + sessionId, e);
        }

        var session = new Session(sessionId, sessionName, defaultWorkingDir, command,
                SessionStatus.IDLE, Instant.now(), Instant.now(), Optional.empty(),
                Optional.ofNullable(context.caseId()).map(UUID::toString),
                Optional.of(roleName));
        registry.register(session);
        sessionMapping.register(roleName, context.caseId(), sessionId);

        List<Capability> capList = capabilities.stream()
                .map(cap -> new Capability(cap, null, null))
                .toList();
        return new Worker(roleName, capList, ctx -> Map.of());
    }
}
