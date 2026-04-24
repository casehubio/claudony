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
    private final String defaultWorkingDir;

    @Inject
    public ClaudonyWorkerProvisioner(
            CaseHubConfig config,
            TmuxService tmux,
            SessionRegistry registry,
            WorkerCommandResolver resolver,
            ClaudonyWorkerContextProvider contextProvider) {
        this(config.enabled(), tmux, registry, resolver, contextProvider,
                config.workers().defaultWorkingDir());
    }

    ClaudonyWorkerProvisioner(boolean enabled, TmuxService tmux, SessionRegistry registry,
                               WorkerCommandResolver resolver,
                               ClaudonyWorkerContextProvider contextProvider,
                               String defaultWorkingDir) {
        this.enabled = enabled;
        this.tmux = tmux;
        this.registry = registry;
        this.resolver = resolver;
        this.contextProvider = contextProvider;
        this.defaultWorkingDir = defaultWorkingDir;
    }

    @Override
    public Worker provision(Set<String> capabilities, ProvisionContext context) {
        if (!enabled) {
            throw new ProvisioningException(
                    "CaseHub integration is disabled — set claudony.casehub.enabled=true");
        }
        String workerId = UUID.randomUUID().toString();
        String command = resolver.resolve(capabilities);

        var task = WorkRequest.of(context.taskType(),
                Map.of("caseId", context.caseId() != null ? context.caseId().toString() : ""));
        contextProvider.buildContext(workerId, task);

        String sessionName = SESSION_PREFIX + workerId;
        try {
            tmux.createSession(sessionName, defaultWorkingDir, command);
        } catch (IOException | InterruptedException e) {
            throw new ProvisioningException("Failed to create tmux session for worker " + workerId, e);
        }

        var session = new Session(workerId, sessionName, defaultWorkingDir, command,
                SessionStatus.IDLE, Instant.now(), Instant.now(), Optional.empty());
        registry.register(session);

        List<Capability> capList = capabilities.stream()
                .map(cap -> new Capability(cap, null, null))
                .toList();
        return new Worker(workerId, capList, ctx -> Map.of());
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
