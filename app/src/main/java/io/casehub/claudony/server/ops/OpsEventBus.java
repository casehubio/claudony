package io.casehub.claudony.server.ops;

import io.casehub.platform.api.registry.RegistryEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

@ApplicationScoped
public class OpsEventBus {

    private final List<MultiEmitter<String>> emitters = new CopyOnWriteArrayList<>();
    private volatile Supplier<String> snapshotFn;

    @SuppressWarnings("unchecked")
    public Multi<String> subscribe(Supplier<String> snapshotFn) {
        this.snapshotFn = snapshotFn;
        return Multi.createFrom().<String>emitter(emitter -> {
            emitter.emit(snapshotFn.get());
            MultiEmitter<String> typed = (MultiEmitter<String>) emitter;
            emitters.add(typed);
            emitter.onTermination(() -> emitters.remove(typed));
        });
    }

    public void emit(String payload) {
        emitters.forEach(e -> { if (!e.isCancelled()) e.emit(payload); });
    }

    void onRegistryEvent(@ObservesAsync RegistryEvent event) {
        if (snapshotFn != null && !emitters.isEmpty()) {
            emit(snapshotFn.get());
        }
    }

    public int subscriberCount() {
        return emitters.size();
    }
}
