package io.casehub.claudony.casehub.fleet;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Capacity-bounded session pool. Manages acquire/suspend/resume/destroy lifecycle with policy-based eviction. */
public class AgentSessionManager {

    private final AgentSessionManagerConfig config;
    private final SessionOperations ops;
    private final Map<String, ManagedSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final EvictionPolicy evictionPolicy;
    private volatile int         effectiveMaxActive;
    private          int         acquireCount;
    private          int         evictionCount;
    private          int         exhaustionCount;


    public AgentSessionManager(AgentSessionManagerConfig config, SessionOperations ops) {this(config, ops, new DefaultEvictionPolicy());}

    public AgentSessionManager(AgentSessionManagerConfig config, SessionOperations ops, EvictionPolicy evictionPolicy) {
        this.config             = config;
        this.ops                = ops;
        this.evictionPolicy     = evictionPolicy;
        this.effectiveMaxActive = config.maxActive();
    }


    public ManagedSession acquireSession(String identity, String workingDir) {
        return acquireSession(identity, workingDir, null, WorkingDirPolicy.EXCLUSIVE);
    }

    public ManagedSession acquireSession(String identity, String workingDir, String command) {
        return acquireSession(identity, workingDir, command, WorkingDirPolicy.EXCLUSIVE);
    }

    public ManagedSession acquireSession(String identity, String workingDir,
                                         String command, WorkingDirPolicy policy) {
        lock.lock();
        try {
            acquireCount++;

            var suspended = sessions.values().stream()
                                    .filter(s -> s.state() == SessionState.SUSPENDED
                                                 && s.identity().equals(identity)
                                                 && s.workingDir().equals(workingDir))
                                    .findFirst();

            if (suspended.isPresent()) {
                return doResume(suspended.get());
            }

            if (policy == WorkingDirPolicy.BRANCH_ISOLATED) {
                throw new UnsupportedOperationException("BRANCH_ISOLATED not yet implemented");
            }

            if (policy == WorkingDirPolicy.EXCLUSIVE) {
                var conflict = sessions.values().stream()
                                       .filter(s -> s.state() == SessionState.ACTIVE
                                                    && s.workingDir().equals(workingDir))
                                       .findFirst();
                if (conflict.isPresent()) {
                    throw new WorkingDirConflictException(workingDir, conflict.get().identity());
                }
            }

            if (activeCount() >= effectiveMaxActive) {
                evictOne();
            }

            return command != null ? doCreate(identity, workingDir, command) : doCreate(identity, workingDir);
        } finally {
            lock.unlock();
        }
    }

    public java.util.List<ManagedSession> activeSessionsForWorkingDir(String workingDir) {
        return sessions.values().stream()
                       .filter(s -> s.state() == SessionState.ACTIVE
                                    && s.workingDir().equals(workingDir))
                       .toList();
    }


    public void suspendSession(String instanceId) {
        lock.lock();
        try {
            var session = sessions.get(instanceId);
            if (session == null || session.state() != SessionState.ACTIVE) return;
            ops.suspend(instanceId);
            session.setState(SessionState.SUSPENDED);
        } finally {
            lock.unlock();
        }
    }

    public ManagedSession resumeSession(String instanceId) {
        lock.lock();
        try {
            var session = sessions.get(instanceId);
            if (session == null || session.state() != SessionState.SUSPENDED) {return null;}

            if (activeCount() >= effectiveMaxActive) {
                evictOne();
            }

            return doResume(session);
        } finally {
            lock.unlock();
        }
    }

    public void recordInteraction(String instanceId, long memoryBytes) {
        var session = sessions.get(instanceId);
        if (session != null) {
            session.recordInteraction(memoryBytes);
        }
    }

    public int adjustMaxActive(int newMax) {
        lock.lock();
        try {
            int clamped = Math.min(newMax, config.maxActive());
            clamped            = Math.max(clamped, config.minActive());
            clamped            = Math.max(clamped, activeCount());
            effectiveMaxActive = clamped;
            return clamped;
        } finally {
            lock.unlock();
        }
    }

    public PoolSnapshot.DemandMetrics snapshotAndResetDemandMetrics() {
        lock.lock();
        try {
            var metrics = new PoolSnapshot.DemandMetrics(evictionCount, exhaustionCount, acquireCount);
            evictionCount   = 0;
            exhaustionCount = 0;
            acquireCount    = 0;
            return metrics;
        } finally {
            lock.unlock();
        }
    }


    public void destroySession(String instanceId) {
        lock.lock();
        try {
            var session = sessions.remove(instanceId);
            if (session == null) return;
            ops.destroy(instanceId);
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            for (var session : sessions.values()) {
                ops.destroy(session.instanceId());
            }
            sessions.clear();
        } finally {
            lock.unlock();
        }
    }

    void registerBootstrapped(String instanceId, String identity, String workingDir,
                              String conversationId, SessionState state) {
        lock.lock();
        try {
            var session = new ManagedSession(instanceId, identity, workingDir, conversationId);
            session.setState(state);
            sessions.put(instanceId, session);
        } finally {
            lock.unlock();
        }
    }


    public ManagedSession getSession(String instanceId) {
        return sessions.get(instanceId);
    }

    public java.util.Collection<ManagedSession> sessions() {
        return java.util.List.copyOf(sessions.values());
    }


    public int activeCount() {
        return (int) sessions.values().stream()
                .filter(s -> s.state() == SessionState.ACTIVE).count();
    }

    public int suspendedCount() {
        return (int) sessions.values().stream()
                .filter(s -> s.state() == SessionState.SUSPENDED).count();
    }

    public AgentPoolStatus status() {
        int active    = activeCount();
        int suspended = suspendedCount();
        return new AgentPoolStatus(
                config.minActive(), effectiveMaxActive,
                active, suspended, active + suspended,
                AgentPoolHealth.HEALTHY
        );
    }

    private ManagedSession doCreate(String identity, String workingDir) {
        String sessionId = ops.create(identity, workingDir);
        String conversationId = ops.conversationId(sessionId);
        var session = new ManagedSession(sessionId, identity, workingDir, conversationId);
        sessions.put(sessionId, session);
        return session;
    }

    private ManagedSession doCreate(String identity, String workingDir, String command) {
        String sessionId = ops.create(identity, workingDir, command);
        String conversationId = ops.conversationId(sessionId);
        var session = new ManagedSession(sessionId, identity, workingDir, conversationId);
        sessions.put(sessionId, session);
        return session;
    }

    private ManagedSession doResume(ManagedSession session) {
        ops.resume(session.instanceId(), session.conversationId(), session.workingDir());
        session.setState(SessionState.ACTIVE);
        return session;
    }

    private void evictOne() {
        int evictable = activeCount() - config.minActive();
        if (evictable <= 0) {
            exhaustionCount++;
            throw new AgentPoolExhaustedException(status());
        }

        Instant now = Instant.now();
        var victim = sessions.values().stream()
                             .filter(s -> s.state() == SessionState.ACTIVE)
                             .max(Comparator.comparingDouble(s -> evictionPolicy.score(s, now)))
                             .orElseThrow(() -> {
                                 exhaustionCount++;
                                 return new AgentPoolExhaustedException(status());
                             });

        ops.suspend(victim.instanceId());
        victim.setState(SessionState.SUSPENDED);
        evictionCount++;
    }
}
