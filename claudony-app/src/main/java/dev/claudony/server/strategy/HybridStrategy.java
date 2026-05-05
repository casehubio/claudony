package dev.claudony.server.strategy;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import java.time.Duration;

/**
 * Extends EventsOnly with a periodic heartbeat tick.
 * 1. Keep-alive — prevents proxy/load-balancer connection timeouts.
 * 2. Drift correction — catches state changes that bypass lifecycle events
 *    (e.g. tmux crash, idle session expiry, manual session delete via dashboard).
 */
public class HybridStrategy extends EventsOnlyStrategy {

    private final Cancellable ticker;

    public HybridStrategy(long heartbeatMs) {
        ticker = Multi.createFrom().ticks().every(Duration.ofMillis(heartbeatMs))
                .subscribe().with(tick -> tickAllCases(), err -> {});
    }

    private void tickAllCases() {
        snapshotFns.forEach((caseId, fn) -> {
            var list = emitters.get(caseId);
            if (list != null && !list.isEmpty()) {
                String snapshot = fn.get();
                list.forEach(e -> { if (!e.isCancelled()) e.emit(snapshot); });
            }
        });
    }

    /** Stop the background heartbeat. Call on application shutdown or in tests. */
    public void cancel() {
        ticker.cancel();
    }
}
