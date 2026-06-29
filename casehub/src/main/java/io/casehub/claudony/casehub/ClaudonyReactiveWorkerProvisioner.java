package io.casehub.claudony.casehub;

import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.ReactiveWorkerProvisioner;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ClaudonyReactiveWorkerProvisioner implements ReactiveWorkerProvisioner {

    private static final Logger LOG = Logger.getLogger(ClaudonyReactiveWorkerProvisioner.class);

    public static final String SESSION_PREFIX = "claudony-worker-";

    // Permanent side-channel: CaseLifecycleEvent deliberately has no causedByEntryId field
    // (shared events must not carry consumer-specific fields — see engine#389 design spec).
    // This map bridges ProvisionResult.causedByEntryId → CaseLedgerEntry.causedByEntryId.
    // Keyed by (tenancyId, caseId); drained when WorkerStarted fires. Safe for concurrent access;
    // one provisioning per case at a time is the architectural invariant.
    private record CausalKey(String tenancyId, UUID caseId) {}
    private final ConcurrentHashMap<CausalKey, UUID> causalContext = new ConcurrentHashMap<>();

    private final boolean enabled;
    private final TmuxService tmux;
    private final SessionRegistry registry;
    private final ProviderConfigSource providerConfigSource;
    private final WorkerSessionMapping sessionMapping;
    private final String defaultCommand;
    private final String defaultWorkingDir;
    // Optional: absent when engine is not on the classpath (non-CaseHub deployments).
    // Used to signal workers.{role}.started=true before delivering ProvisionResult to
    // the engine, ensuring the event bus queue position prevents re-provisioning on the
    // engine's own provisioning context patch.
    private final Instance<CaseHubRuntime> caseHubRuntime;

    private final ClaudonyWorkerExecutionManager execManager;
    private final QhorusCausalLinkResolver causalLinkResolver;

    @Inject
    public ClaudonyReactiveWorkerProvisioner(
            CaseHubConfig config,
            TmuxService tmux,
            SessionRegistry registry,
            ProviderConfigSource providerConfigSource,
            WorkerSessionMapping sessionMapping,
            Instance<CaseHubRuntime> caseHubRuntime,
            @WorkerBackend ClaudonyWorkerExecutionManager execManager,
            QhorusCausalLinkResolver causalLinkResolver) {
        this(config.enabled(), tmux, registry, providerConfigSource, sessionMapping,
                config.workers().defaultCommand(), config.workers().defaultWorkingDir(),
                caseHubRuntime, execManager, causalLinkResolver);
    }

    ClaudonyReactiveWorkerProvisioner(boolean enabled, TmuxService tmux, SessionRegistry registry,
                                       ProviderConfigSource providerConfigSource,
                                       WorkerSessionMapping sessionMapping,
                                       String defaultCommand,
                                       String defaultWorkingDir,
                                       Instance<CaseHubRuntime> caseHubRuntime,
                                       ClaudonyWorkerExecutionManager execManager,
                                       QhorusCausalLinkResolver causalLinkResolver) {
        this.enabled = enabled;
        this.tmux = tmux;
        this.registry = registry;
        this.providerConfigSource = providerConfigSource;
        this.sessionMapping = sessionMapping;
        this.defaultCommand = defaultCommand;
        this.defaultWorkingDir = defaultWorkingDir;
        this.caseHubRuntime = caseHubRuntime;
        this.execManager = execManager;
        this.causalLinkResolver = causalLinkResolver;
    }

    @Override
    public Uni<ProvisionResult> provision(Set<String> capabilities, ProvisionContext context) {
        Uni<Void> setup = Uni.createFrom()
            .<Void>item(() -> { setupSession(capabilities, context); return null; })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());

        Uni<Optional<UUID>> causedBy =
            (causalLinkResolver != null
             && context.triggerChannelId() != null
             && context.triggerCorrelationId() != null)
            ? causalLinkResolver.resolve(context.triggerChannelId(), context.triggerCorrelationId())
            : Uni.createFrom().item(Optional.empty());

        return Uni.combine().all().unis(setup, causedBy).asTuple()
            .invoke(tuple -> {
                if (context.caseId() != null) {
                    tuple.getItem2().ifPresent(id -> causalContext.put(new CausalKey(context.tenancyId(), context.caseId()), id));
                }
            })
            .map(tuple -> new ProvisionResult(tuple.getItem2().orElse(null)))
            .call(result -> signalStarted(capabilities, context))
            // startWatcher() calls .await() — must run on worker pool, not event loop
            .emitOn(Infrastructure.getDefaultWorkerPool())
            .invoke(result -> startWatcher(capabilities, context));
    }

    private void startWatcher(Set<String> capabilities, ProvisionContext context) {
        if (context.caseId() == null || execManager == null) return;
        String roleName = context.taskType() != null
                ? context.taskType()
                : capabilities.stream().findFirst().orElse("worker");
        execManager.startWatcherForSession(context.caseId(), roleName);
    }

    /**
     * Signals workers.{role}.started=true into the case context BEFORE delivering
     * ProvisionResult to the engine. This queues the signal on the Vert.x event bus
     * ahead of the engine's own provisioning context patch, so that when the engine's
     * CONTEXT_CHANGED fires next, the when-guard (.workers.agent.started != true)
     * is already false — preventing duplicate provisioning.
     */
    private Uni<Void> signalStarted(Set<String> capabilities, ProvisionContext context) {
        if (context.caseId() == null || caseHubRuntime == null || caseHubRuntime.isUnsatisfied()) {
            return Uni.createFrom().voidItem();
        }
        String roleName = context.taskType() != null
                ? context.taskType()
                : capabilities.stream().findFirst().orElse("worker");
        CaseHubRuntimeCompat.signal(caseHubRuntime.get(),
                context.caseId(), "workers." + roleName + ".started", true);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> terminate(String workerId, String tenancyId) {
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
        return Uni.createFrom().item(providerConfigSource.declaredAgentIds());
    }

    /**
     * Drains the causal context entry for the given tenancyId and caseId.
     * Called by {@link io.casehub.claudony.casehub.ClaudonyLedgerEventCapture} when
     * a WorkerStarted event is observed.
     */
    UUID drainCausalContext(String tenancyId, UUID caseId) {
        return causalContext.remove(new CausalKey(tenancyId, caseId));
    }

    /** Seeded by tests to simulate a resolved causedByEntryId without engine#231. */
    void seedCausalContextForTest(String tenancyId, UUID caseId, UUID entryId) {
        causalContext.put(new CausalKey(tenancyId, caseId), entryId);
    }

    private void setupSession(Set<String> capabilities, ProvisionContext context) {
        if (!enabled) {
            throw new ProvisioningException(
                    "CaseHub integration is disabled — set claudony.casehub.enabled=true");
        }
        String sessionId = UUID.randomUUID().toString();
        String roleName = context.taskType() != null
                ? context.taskType()
                : capabilities.stream().findFirst().orElse("worker");

        ClaudonyProviderConfig config = providerConfigSource.forAgent(roleName);
        String baseCommand = config.command().orElse(defaultCommand);

        Optional<String> meshPrompt = Optional.ofNullable(context.workerContext())
                .map(wc -> wc.properties().get("systemPrompt"))
                .filter(String.class::isInstance)
                .map(String.class::cast);

        String enrichedCommand = WorkerCommandBuilder.build(baseCommand, config, meshPrompt);
        String effectiveWorkingDir = config.workingDir().orElse(defaultWorkingDir);
        String sessionName = SESSION_PREFIX + sessionId;

        try {
            tmux.createWorkerSession(sessionName, effectiveWorkingDir, enrichedCommand);
            // Persist caseId and roleName in tmux session options for recovery after server restart
            if (context.caseId() != null) {
                tmux.setSessionOption(sessionName, "@casehub_case_id", context.caseId().toString());
                tmux.setSessionOption(sessionName, "@casehub_role", roleName);
                tmux.setSessionOption(sessionName, "@casehub_tenant_id", context.tenancyId());
            }
        } catch (IOException | InterruptedException e) {
            throw new ProvisioningException("Failed to create tmux session for worker " + sessionId, e);
        }

        var session = new Session(sessionId, sessionName, effectiveWorkingDir, enrichedCommand,
                SessionStatus.IDLE, Instant.now(), Instant.now(), Optional.empty(),
                Optional.ofNullable(context.caseId()).map(UUID::toString),
                Optional.of(roleName), context.tenancyId());
        registry.register(session);
        sessionMapping.register(roleName, context.caseId(), sessionId);
    }
}
