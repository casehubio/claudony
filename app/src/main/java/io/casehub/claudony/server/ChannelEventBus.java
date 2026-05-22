package io.casehub.claudony.server;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process SSE fan-out for channel messages.
 * Emits integer ticks keyed by channel name; receivers fetch the actual
 * messages via QhorusDashboardService to get properly-formatted timeline entries.
 */
@ApplicationScoped
public class ChannelEventBus {

    private final ConcurrentHashMap<String, List<MultiEmitter<Integer>>> subscribers =
            new ConcurrentHashMap<>();

    public Multi<Integer> subscribe(String channelName) {
        return Multi.createFrom().emitter(emitter -> {
            @SuppressWarnings("unchecked")
            MultiEmitter<Integer> typed = (MultiEmitter<Integer>) emitter;
            subscribers.computeIfAbsent(channelName, k -> new CopyOnWriteArrayList<>()).add(typed);
            emitter.onTermination(() -> removeSubscriber(channelName, typed));
        });
    }

    public void emit(String channelName) {
        List<MultiEmitter<Integer>> list = subscribers.get(channelName);
        if (list == null) return;
        list.forEach(em -> { if (!em.isCancelled()) em.emit(1); });
    }

    /** Package-private for testing. */
    int subscriberCount(String channelName) {
        List<MultiEmitter<Integer>> list = subscribers.get(channelName);
        return list == null ? 0 : list.size();
    }

    private void removeSubscriber(String channelName, MultiEmitter<Integer> emitter) {
        subscribers.computeIfPresent(channelName, (key, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
