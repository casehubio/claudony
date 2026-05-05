package dev.claudony.server;

import dev.claudony.config.ClaudonyConfig;
import dev.claudony.server.strategy.EventsOnlyStrategy;
import dev.claudony.server.strategy.HybridStrategy;
import dev.claudony.server.strategy.RegistryHooksStrategy;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Orchestrates SSE push for the case worker panel.
 *
 * <p>Observes {@link WorkerCaseLifecycleEvent} CDI events (fired by
 * ClaudonyWorkerStatusListener in claudony-casehub) and fans out snapshots
 * to all active SSE subscribers for the affected case.
 *
 * <p>Strategy is selected from config at startup (events-only | hybrid | registry-hooks).
 */
@ApplicationScoped
public class CaseEventBroadcaster {

    private static final Logger LOG = Logger.getLogger(CaseEventBroadcaster.class);

    @Inject ClaudonyConfig config;
    @Inject SessionRegistry registry;

    private CaseWorkerUpdateStrategy strategy;

    @PostConstruct
    void init() {
        String strategyName = config.caseWorkerUpdate();
        long heartbeatMs = config.caseWorkerHeartbeatMs();
        strategy = switch (strategyName) {
            case "events-only" -> new EventsOnlyStrategy();
            case "registry-hooks" -> {
                var s = new RegistryHooksStrategy();
                registry.addChangeListener(this::emit);
                yield s;
            }
            default -> {
                LOG.infof("Case worker SSE: using hybrid strategy (heartbeat=%dms)", heartbeatMs);
                yield new HybridStrategy(heartbeatMs);
            }
        };
        LOG.infof("Case worker SSE strategy: %s", strategyName);
    }

    /** Observes CDI lifecycle events from ClaudonyWorkerStatusListener. */
    void onWorkerLifecycleEvent(@Observes WorkerCaseLifecycleEvent event) {
        emit(event.caseId());
    }

    /** Pushes a fresh snapshot to all SSE subscribers for the given case. */
    public void emit(String caseId) {
        if (caseId == null) return;
        strategy.onLifecycleEvent(caseId);
    }

    /**
     * Returns an SSE Multi for the given case.
     * The first item is the current snapshot; subsequent items pushed on lifecycle events.
     */
    public Multi<String> subscribe(String caseId, Supplier<String> snapshotFn) {
        return strategy.subscribe(caseId, snapshotFn);
    }

    /** Returns strategy type name. Package-private for testing. */
    String strategyType() {
        return switch (strategy) {
            case RegistryHooksStrategy ignored -> "registry-hooks";
            case HybridStrategy ignored -> "hybrid";
            default -> "events-only";
        };
    }
}
