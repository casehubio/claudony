package io.casehub.claudony.casehub.fleet;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class AgentPool {

    private final AgentPoolConfig config;
    private final Supplier<String> sessionFactory;
    private final Consumer<String> sessionDestroyer;

    private final ConcurrentLinkedDeque<String> idle = new ConcurrentLinkedDeque<>();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ReentrantLock lock = new ReentrantLock();

    private volatile AgentPoolHealth health = AgentPoolHealth.HEALTHY;

    public AgentPool(AgentPoolConfig config,
                     Supplier<String> sessionFactory,
                     Consumer<String> sessionDestroyer) {
        this.config = config;
        this.sessionFactory = sessionFactory;
        this.sessionDestroyer = sessionDestroyer;
    }

    public void preWarm() {
        lock.lock();
        try {
            while (idle.size() + active.size() < config.min()) {
                idle.addLast(sessionFactory.get());
            }
        } finally {
            lock.unlock();
        }
    }

    public String acquire() {
        lock.lock();
        try {
            String sessionId = idle.pollFirst();
            if (sessionId != null) {
                active.add(sessionId);
                return sessionId;
            }
            if (active.size() >= config.max()) {
                throw new AgentPoolExhaustedException(status());
            }
            sessionId = sessionFactory.get();
            active.add(sessionId);
            return sessionId;
        } finally {
            lock.unlock();
        }
    }

    public void release(String sessionId) {
        lock.lock();
        try {
            if (!active.remove(sessionId)) {
                return;
            }
            sessionDestroyer.accept(sessionId);
            if (idle.size() + active.size() < config.min()) {
                idle.addLast(sessionFactory.get());
            }
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            for (String sessionId : active) {
                sessionDestroyer.accept(sessionId);
            }
            active.clear();
            for (String sessionId : idle) {
                sessionDestroyer.accept(sessionId);
            }
            idle.clear();
        } finally {
            lock.unlock();
        }
    }

    public AgentPoolStatus status() {
        int activeCount = active.size();
        int idleCount = idle.size();
        return new AgentPoolStatus(
                config.min(), config.max(),
                activeCount, idleCount,
                activeCount + idleCount,
                health
        );
    }
}
