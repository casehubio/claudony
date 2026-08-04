package io.casehub.claudony.casehub.inbox;

import io.casehub.claudony.casehub.ClaudonyWorkerStatusListener.WorkerStalledEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class StallTracker {

    private final Set<String> stalled = ConcurrentHashMap.newKeySet();

    void onStall(@Observes WorkerStalledEvent event) {
        stalled.add(event.workerId());
    }

    public void markStalled(String workerId) {
        stalled.add(workerId);
    }

    public void clearStall(String workerId) {
        stalled.remove(workerId);
    }

    public boolean isStalled(String workerId) {
        return stalled.contains(workerId);
    }

    public Set<String> stalledWorkerIds() {
        return Collections.unmodifiableSet(stalled);
    }
}
