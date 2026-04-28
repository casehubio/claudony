package dev.claudony.casehub;

import dev.claudony.server.SessionRegistry;
import dev.claudony.server.TmuxService;
import dev.claudony.server.model.Session;
import dev.claudony.server.model.SessionStatus;
import io.casehub.api.model.Capability;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.WorkerProvisioner;
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
public class ClaudonyWorkerProvisioner implements WorkerProvisioner {

    static final String SESSION_PREFIX = "claudony-worker-";

    private final boolean enabled;
    private final TmuxService tmux;
    private final SessionRegistry registry;
    private final WorkerCommandResolver resolver;
    private final ClaudonyWorkerContextProvider contextProvider;
    private final WorkerSessionMapping sessionMapping;
    private final String defaultWorkingDir;

    @Inject
    public ClaudonyWorkerProvisioner(
            CaseHubConfig config,
            TmuxService tmux,
            SessionRegistry registry,
            WorkerCommandResolver resolver,
            ClaudonyWorkerContextProvider contextProvider,
            WorkerSessionMapping sessionMapping) {
        this(config.enabled(), tmux, registry, resolver, contextProvider, sessionMapping,
                config.workers().defaultWorkingDir());
    }

    ClaudonyWorkerProvisioner(boolean enabled, TmuxService tmux, SessionRegistry registry,
                               WorkerCommandResolver resolver,
                               ClaudonyWorkerContextProvider contextProvider,
                               WorkerSessionMapping sessionMapping,
                               String defaultWorkingDir) {
        this.enabled = enabled;
        this.tmux = tmux;
        this.registry = registry;
        this.resolver = resolver;
        this.contextProvider = contextProvider;
        this.sessionMapping = sessionMapping;
        this.defaultWorkingDir = defaultWorkingDir;
    }

    @Override
    public Worker provision(Set<String> capabilities, ProvisionContext context) {
        if (!enabled) {
            throw new ProvisioningException(
                    "CaseHub integration is disabled — set claudony.casehub.enabled=true");
        }
        // roleName = taskType from the case definition — used as Worker.name so
        // WorkResultSubmitter can find the worker by name in the case definition.
        // sessionId = UUID — unique tmux session identity, tracked separately.
        String sessionId = UUID.randomUUID().toString();
        String roleName = context.taskType() != null ? context.taskType() : capabilities.stream().findFirst().orElse("worker");
        String command = resolver.resolve(capabilities);

        var task = WorkRequest.of(roleName,
                Map.of("caseId", context.caseId() != null ? context.caseId().toString() : ""));
        contextProvider.buildContext(sessionId, task);

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

    @Override
    public void terminate(String workerId) {
        try {
            tmux.killSession(SESSION_PREFIX + workerId);
        } catch (IOException | InterruptedException e) {
            // Session may already be gone — no-op
        }
        registry.remove(workerId);
    }

    @Override
    public Set<String> getCapabilities() {
        return resolver.getAvailableCapabilities();
    }
}
