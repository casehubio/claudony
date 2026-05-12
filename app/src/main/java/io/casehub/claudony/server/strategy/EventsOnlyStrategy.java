package io.casehub.claudony.server.strategy;

import io.casehub.claudony.server.CaseWorkerUpdateStrategy;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Emits SSE snapshots only when a lifecycle event fires.
 * No background tick — zero overhead when no events occur.
 */
public class EventsOnlyStrategy implements CaseWorkerUpdateStrategy {

    // Package-private for testing and subclass access (HybridStrategy, RegistryHooksStrategy)
    final ConcurrentHashMap<String, List<MultiEmitter<String>>> emitters = new ConcurrentHashMap<>();
    // Last-registered snapshotFn per caseId wins. This is safe because all subscribers for a
    // case use the same snapshot producer (SessionResource.buildCaseSnapshot), so overwriting
    // is idempotent. Do not use this strategy if multiple distinct producers exist per case.
    final ConcurrentHashMap<String, Supplier<String>> snapshotFns = new ConcurrentHashMap<>();

    @Override
    public void onLifecycleEvent(String caseId) {
        Supplier<String> fn = snapshotFns.get(caseId);
        if (fn == null) return;
        String snapshot = fn.get();
        List<MultiEmitter<String>> list = emitters.get(caseId);
        if (list == null) return;
        list.forEach(e -> { if (!e.isCancelled()) e.emit(snapshot); });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Multi<String> subscribe(String caseId, Supplier<String> snapshotFn) {
        snapshotFns.put(caseId, snapshotFn);
        return Multi.createFrom().<String>emitter(emitter -> {
            emitter.emit(snapshotFn.get()); // initial snapshot on connect
            MultiEmitter<String> typed = (MultiEmitter<String>) emitter;
            emitters.computeIfAbsent(caseId, k -> new CopyOnWriteArrayList<>()).add(typed);
            emitter.onTermination(() -> removeEmitter(caseId, typed));
        });
    }

    /** Number of active emitters for a case. Package-private for testing. */
    int emitterCount(String caseId) {
        List<MultiEmitter<String>> list = emitters.get(caseId);
        return list == null ? 0 : list.size();
    }

    private void removeEmitter(String caseId, MultiEmitter<String> emitter) {
        List<MultiEmitter<String>> list = emitters.get(caseId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(caseId);
                snapshotFns.remove(caseId);
            }
        }
    }
}
