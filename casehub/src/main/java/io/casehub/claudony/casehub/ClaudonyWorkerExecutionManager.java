package io.casehub.claudony.casehub;

import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.PreDestroy;
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

    @Inject
    public ClaudonyWorkerExecutionManager(
            TmuxService tmuxService,
            SessionRegistry registry,
            WorkerSessionMapping sessionMapping,
            CaseHubConfig config,
            EventBus eventBus) {
        this.tmuxService = tmuxService;
        this.registry = registry;
        this.sessionMapping = sessionMapping;
        this.config = config;
        this.eventBus = eventBus;
    }

    @Override
    public Uni<Void> submit(Long eventLogId, CaseInstance instance, Worker worker,
                            Capability capability, Map<String, Object> inputData) {
        if (!config.enabled()) return Uni.createFrom().voidItem();

        var caseId = instance.getUuid();
        var roleName = worker.getName();
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
        sessionToRole.put(sessionId, worker.getName());
        if (watchers.putIfAbsent(sessionId, watcher) != null) {
            sessionToRole.remove(sessionId);
            LOG.warnf("Duplicate watch request for session %s — ignoring", sessionId);
            return;
        }
        watcher.start();
    }

    @Override
    public Uni<Void> schedulePersistedEvent(EventLog scheduledEventLog) {
        // Tmux workers have no Quartz persistent events — no-op
        return Uni.createFrom().voidItem();
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
                    if (registry.find(sessionId).isEmpty()) break; // terminated — no publish

                    try {
                        final boolean exists = tmuxService.sessionExists(sessionName);
                        consecutiveFailures = 0;
                        if (!exists) {
                            // Atomic gate: whichever caller wins registry.remove() publishes
                            if (registry.remove(sessionId) != null) {
                                pendingExitSignals.put(instance.getUuid(), worker.getName()); // store before send
                                final String idempotencyKey =
                                        instance.getUuid() + ":" + worker.getName() + ":" + sessionId;
                                eventBus.send(EventBusAddresses.WORKER_EXECUTION_FINISHED,
                                        WorkflowExecutionCompleted.approved(instance, worker, idempotencyKey, Map.of()));
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
