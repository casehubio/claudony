package io.casehub.claudony.testing.fleet;

import io.casehub.claudony.casehub.fleet.SessionOperations;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemorySessionOperations implements SessionOperations {

    private record SessionRecord(String identity, String workingDir) {}

    private final ConcurrentHashMap<String, SessionRecord> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionRecord> suspended = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> conversationIds = new ConcurrentHashMap<>();

    private final AtomicInteger counter = new AtomicInteger();
    private final AtomicInteger createCounter = new AtomicInteger();
    private final AtomicInteger suspendCounter = new AtomicInteger();
    private final AtomicInteger resumeCounter = new AtomicInteger();
    private final AtomicInteger destroyCounter = new AtomicInteger();

    private volatile long defaultMemoryBytes = 100L * 1024 * 1024;

    @Override
    public String create(String identity, String workingDir) {
        String sessionId = "mem-" + counter.incrementAndGet();
        String conversationId = UUID.randomUUID().toString();
        active.put(sessionId, new SessionRecord(identity, workingDir));
        conversationIds.put(sessionId, conversationId);
        createCounter.incrementAndGet();
        return sessionId;
    }

    @Override
    public String create(String identity, String workingDir, String command) {
        return create(identity, workingDir);
    }

    @Override
    public String conversationId(String sessionId) {
        return conversationIds.get(sessionId);
    }

    @Override
    public void suspend(String sessionId) {
        var record = active.remove(sessionId);
        if (record != null) {
            suspended.put(sessionId, record);
        }
        suspendCounter.incrementAndGet();
    }

    @Override
    public void resume(String sessionId, String conversationId, String workingDir) {
        var record = suspended.remove(sessionId);
        if (record != null) {
            active.put(sessionId, record);
        }
        resumeCounter.incrementAndGet();
    }

    @Override
    public void destroy(String sessionId) {
        active.remove(sessionId);
        suspended.remove(sessionId);
        conversationIds.remove(sessionId);
        destroyCounter.incrementAndGet();
    }

    @Override
    public long memoryBytes(String sessionId) {
        return defaultMemoryBytes;
    }

    public void setDefaultMemoryBytes(long bytes) {
        this.defaultMemoryBytes = bytes;
    }

    public int createCount() { return createCounter.get(); }
    public int suspendCount() { return suspendCounter.get(); }
    public int resumeCount() { return resumeCounter.get(); }
    public int destroyCount() { return destroyCounter.get(); }

    public Set<String> activeSessions() {
        return Collections.unmodifiableSet(active.keySet());
    }

    public Set<String> suspendedSessions() {
        return Collections.unmodifiableSet(suspended.keySet());
    }

    public boolean isActive(String sessionId) {
        return active.containsKey(sessionId);
    }

    public boolean isSuspended(String sessionId) {
        return suspended.containsKey(sessionId);
    }

    public void reset() {
        active.clear();
        suspended.clear();
        conversationIds.clear();
        counter.set(0);
        createCounter.set(0);
        suspendCounter.set(0);
        resumeCounter.set(0);
        destroyCounter.set(0);
    }
}
