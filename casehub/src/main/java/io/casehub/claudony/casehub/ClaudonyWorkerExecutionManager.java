package io.casehub.claudony.casehub;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements WorkerExecutionManager for Claudony's tmux-based workers.
 * submit() starts a virtual thread that polls tmux has-session and publishes
 * WorkflowExecutionCompleted when the session exits naturally. Cancellation
 * is signalled by removing the session from SessionRegistry before killing tmux.
 */
@WorkerBackend
@Priority(10)
@ApplicationScoped
public class ClaudonyWorkerExecutionManager implements WorkerExecutionManager {

    private static final Logger LOG = Logger.getLogger(ClaudonyWorkerExecutionManager.class);

    private final TmuxService tmuxService;
    private final SessionRegistry registry;
    private final WorkerSessionMapping sessionMapping;
    private final CaseHubConfig config;
    private final EventBus eventBus;

    /** Active watcher threads keyed by sessionId — used for shutdown and duplicate guard. */
    private final ConcurrentHashMap<String, Thread> watchers = new ConcurrentHashMap<>();
    /** sessionId → roleName, for getActiveWorkCount() lookups without iterating SessionRegistry. */
    private final ConcurrentHashMap<String, String> sessionToRole = new ConcurrentHashMap<>();
    /** caseId → roleName for exit signals awaiting ledger capture drain. */
    private final ConcurrentHashMap<UUID, String> pendingExitSignals = new ConcurrentHashMap<>();

    private CrossTenantCaseInstanceRepository caseInstanceRepository;

    @Inject
    public ClaudonyWorkerExecutionManager(
            TmuxService tmuxService,
            SessionRegistry registry,
            WorkerSessionMapping sessionMapping,
            CaseHubConfig config,
            EventBus eventBus,
            CrossTenantCaseInstanceRepository caseInstanceRepository) {
        this.tmuxService = tmuxService;
        this.registry = registry;
        this.sessionMapping = sessionMapping;
        this.config = config;
        this.eventBus = eventBus;
        this.caseInstanceRepository = caseInstanceRepository;
    }

    @Override
    public boolean supports(String capabilityName, String tenancyId) {
        return true;
    }

    @Override
    public Uni<Void> submit(Long eventLogId, CaseInstance instance, Worker worker,
                            Capability capability, Map<String, Object> inputData) {
        if (!config.enabled()) return Uni.createFrom().voidItem();

        var caseId = instance.getUuid();
        var roleName = worker.name();
        var sessionIdOpt = sessionMapping.findByCase(caseId.toString(), roleName);
        if (sessionIdOpt.isEmpty()) {
            LOG.warnf("No session found for case %s / role %s — watcher not started", caseId, roleName);
            return Uni.createFrom().voidItem();
        }

        var sessionId = sessionIdOpt.get();
        var sessionName = ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX + sessionId;
        watch(sessionId, sessionName, instance, worker);
        return Uni.createFrom().voidItem();
    }

    /** Direct entry point used by recovery path (bypasses sessionMapping which is empty after restart). */
    public void watch(String sessionId, String sessionName, CaseInstance instance, Worker worker) {
        final Runnable runnable = watcherRunnable(sessionId, sessionName, instance, worker);
        final Thread watcher = Thread.ofVirtual()
                .name("casehub-watcher-" + sessionId)
                .unstarted(runnable);

        // Put role before putIfAbsent so getActiveWorkCount() never transiently undercounts
        sessionToRole.put(sessionId, worker.name());
        if (watchers.putIfAbsent(sessionId, watcher) != null) {
            sessionToRole.remove(sessionId);
            LOG.warnf("Duplicate watch request for session %s — ignoring", sessionId);
            return;
        }
        watcher.start();
    }

    /**
     * Starts the exit watcher for a just-provisioned worker session.
     * Called from ClaudonyReactiveWorkerProvisioner after provision() succeeds when
     * the tryProvision() path in CaseContextChangedEventHandler bypasses WorkerScheduleEvent
     * (because DefaultWorkOrchestrator is excluded), so submit() is never called via the
     * normal WorkerScheduleEventHandler path.
     */
    public void startWatcherForSession(UUID caseId, String roleName) {
        if (caseInstanceRepository == null) return; // unit test context without CDI
        String sessionId = sessionMapping.findByCase(caseId.toString(), roleName).orElse(null);
        if (sessionId == null) {
            LOG.warnf("startWatcherForSession: no session for case %s role %s", caseId, roleName);
            return;
        }
        CaseInstance instance = caseInstanceRepository.findByUuid(caseId);
        if (instance == null) {
            LOG.warnf("startWatcherForSession: case %s not found", caseId);
            return;
        }
        String sessionName = ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX + sessionId;
        var worker = Worker.builder()
                .name(roleName)
                .capabilityName(roleName)
                .function(new WorkerFunction.Sync(ctx -> WorkerResult.of(java.util.Map.of())))
                .build();
        watch(sessionId, sessionName, instance, worker);
    }


    @Override
    public int getActiveWorkCount(String workerId) {
        // The engine passes the worker definition name as workerId;
        // Claudony stores this as roleName in sessionToRole — they are the same string.
        return (int) sessionToRole.values().stream()
                .filter(workerId::equals)
                .count();
    }

    @PreDestroy
    public void shutdown() {
        // Interrupt all threads, then clear the maps. Each watcher's finally block also calls
        // watchers.remove() — those calls are no-ops after clear(). Note: activeWatcherCount()
        // returns 0 after this call even while threads are still unwinding; it reflects map state,
        // not thread liveness.
        watchers.values().forEach(Thread::interrupt);
        watchers.clear();
        sessionToRole.clear();
    }

    /** Package-private for testing — returns current number of active watcher threads. */
    int activeWatcherCount() {
        return watchers.size();
    }

    /** Drains and returns the pending exit role name for this case. Called by ClaudonyLedgerEventCapture on WorkerExecutionCompleted. Returns null if no signal is pending. */
    public String drainExitSignal(UUID caseId) {
        return pendingExitSignals.remove(caseId);
    }

    private Runnable watcherRunnable(String sessionId, String sessionName,
                                     CaseInstance instance, Worker worker) {
        return () -> {
            final long pollMs = config.workerExitPollMs();
            final int maxFailures = config.workerExitMaxPollFailures();
            int consecutiveFailures = 0;

            try {
                do {
                    if (Thread.currentThread().isInterrupted()) break;
                    if (registry.findUnscoped(sessionId).isEmpty()) break; // terminated — no publish

                    try {
                        final boolean exists = tmuxService.sessionExists(sessionName);
                        consecutiveFailures = 0;
                        if (!exists) {
                            // Atomic gate: whichever caller wins registry.remove() publishes
                            if (registry.remove(sessionId) != null) {
                                pendingExitSignals.put(instance.getUuid(), worker.name()); // store before send
                                final String idempotencyKey =
                                        instance.getUuid() + ":" + worker.name() + ":" + sessionId;
                                eventBus.send(EventBusAddresses.WORKER_EXECUTION_FINISHED,
                                        WorkflowExecutionCompleted.approved(instance, worker, idempotencyKey, Map.of(), null));
                            }
                            break;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (IOException e) {
                        consecutiveFailures++;
                        if (consecutiveFailures >= maxFailures) {
                            LOG.errorf("Session %s: %d consecutive poll failures — abandoning watcher",
                                    sessionId, maxFailures);
                            break;
                        }
                        LOG.warnf("Session %s: poll failure (%d/%d): %s",
                                sessionId, consecutiveFailures, maxFailures, e.getMessage());
                    }

                    try {
                        Thread.sleep(pollMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } while (true);
            } finally {
                watchers.remove(sessionId);
                sessionToRole.remove(sessionId);
            }
        };
    }
}
