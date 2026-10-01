package io.casehub.claudony.server.fleet;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

@ApplicationScoped
public class PoolEventBus {

    private final ConcurrentHashMap<String, List<MultiEmitter<String>>> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Supplier<String>> snapshotFns = new ConcurrentHashMap<>();

    public void emit(String poolName, String payload) {
        List<MultiEmitter<String>> list = emitters.get(poolName);
        if (list == null) return;
        list.forEach(e -> { if (!e.isCancelled()) e.emit(payload); });
    }

    @SuppressWarnings("unchecked")
    public Multi<String> subscribe(String poolName, Supplier<String> snapshotFn) {
        snapshotFns.put(poolName, snapshotFn);
        return Multi.createFrom().<String>emitter(emitter -> {
            emitter.emit(snapshotFn.get());
            MultiEmitter<String> typed = (MultiEmitter<String>) emitter;
            emitters.computeIfAbsent(poolName, k -> new CopyOnWriteArrayList<>()).add(typed);
            emitter.onTermination(() -> removeEmitter(poolName, typed));
        });
    }

    public int subscriberCount(String poolName) {
        List<MultiEmitter<String>> list = emitters.get(poolName);
        return list == null ? 0 : list.size();
    }

    private void removeEmitter(String poolName, MultiEmitter<String> emitter) {
        List<MultiEmitter<String>> list = emitters.get(poolName);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(poolName);
                snapshotFns.remove(poolName);
            }
        }
    }
}
