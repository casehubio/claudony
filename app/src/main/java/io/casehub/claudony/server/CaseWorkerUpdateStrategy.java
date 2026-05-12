package io.casehub.claudony.server;

import io.smallrye.mutiny.Multi;
import java.util.function.Supplier;

/**
 * SPI controlling how the case worker panel SSE stream receives updates.
 * Selected via {@code claudony.case-worker-update} config property.
 *
 * <p>Implementations: events-only, hybrid (default), registry-hooks.
 * See casehubio/parent#11 for per-case runtime selection (future work).
 */
public interface CaseWorkerUpdateStrategy {

    /**
     * Called when a CaseHub worker lifecycle event fires for the given case.
     * Implementations push a fresh snapshot to all active subscribers.
     */
    void onLifecycleEvent(String caseId);

    /**
     * Returns a Multi that emits SSE payloads for the given case.
     * The first item MUST be the current snapshot (ensures reconnects show fresh state).
     *
     * @param caseId     the case to subscribe to
     * @param snapshotFn produces {@code "data: <JSON>\n\n"} on demand; called on the calling thread
     */
    Multi<String> subscribe(String caseId, Supplier<String> snapshotFn);
}
