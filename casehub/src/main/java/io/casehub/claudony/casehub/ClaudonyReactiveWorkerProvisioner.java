package io.casehub.claudony.casehub;

import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.ReactiveWorkerProvisioner;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ClaudonyReactiveWorkerProvisioner implements ReactiveWorkerProvisioner {

    public static final String SESSION_PREFIX = "claudony-worker-";

    // Bridges causedByEntryId from provision() to ClaudonyLedgerEventCapture.
    // Keyed by caseId; drained when WorkerStarted fires. Safe for concurrent access;
    // one provisioning per case at a time is the architectural invariant.
    private final ConcurrentHashMap<UUID, UUID> causalContext = new ConcurrentHashMap<>();

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
    public Uni<ProvisionResult> provision(Set<String> capabilities, ProvisionContext context) {
        return Uni.createFrom()
                  .item(() -> doProvision(capabilities, context))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> terminate(String workerId) {
        return Uni.createFrom()
                  .<Void>item(() -> {
                      // Remove from registry FIRST — this is the watcher's cancellation signal.
                      // The watcher checks registry.find() at the top of each loop; removing here
                      // before killing tmux ensures it exits cleanly without publishing a false completion.
                      registry.remove(workerId);
                      try {
                          tmux.killSession(SESSION_PREFIX + workerId);
                      } catch (IOException | InterruptedException e) {
                          // Session may already be gone — no-op
                      }
                      return null;
                  })
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Set<String>> getCapabilities() {
        return Uni.createFrom().item(resolver.getAvailableCapabilities());
    }

    /**
     * Drains the causal context entry for the given caseId.
     * Called by {@link io.casehub.claudony.casehub.ClaudonyLedgerEventCapture} when
     * a WorkerStarted event is observed.
     */
    UUID drainCausalContext(UUID caseId) {
        return causalContext.remove(caseId);
    }

    /** Seeded by tests to simulate a resolved causedByEntryId without engine#231. */
    void seedCausalContextForTest(UUID caseId, UUID entryId) {
        causalContext.put(caseId, entryId);
    }

    private ProvisionResult doProvision(Set<String> capabilities, ProvisionContext context) {
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
            tmux.createWorkerSession(sessionName, defaultWorkingDir, command);
            // Persist caseId and roleName in tmux session options for recovery after server restart
            if (context.caseId() != null) {
                tmux.setSessionOption(sessionName, "@casehub_case_id", context.caseId().toString());
                tmux.setSessionOption(sessionName, "@casehub_role", roleName);
            }
        } catch (IOException | InterruptedException e) {
            throw new ProvisioningException("Failed to create tmux session for worker " + sessionId, e);
        }

        var session = new Session(sessionId, sessionName, defaultWorkingDir, command,
                SessionStatus.IDLE, Instant.now(), Instant.now(), Optional.empty(),
                Optional.ofNullable(context.caseId()).map(UUID::toString),
                Optional.of(roleName));
        registry.register(session);
        sessionMapping.register(roleName, context.caseId(), sessionId);

        // TODO(engine#231): when triggerChannelId + triggerCorrelationId are non-null,
        // look up MessageLedgerEntry by (channelId, correlationId) via Qhorus, store
        // (caseId → entryId) in causalContext, and return ProvisionResult(entryId).
        // ClaudonyLedgerEventCapture drains causalContext on WorkerStarted.
        return ProvisionResult.empty();
    }
}
