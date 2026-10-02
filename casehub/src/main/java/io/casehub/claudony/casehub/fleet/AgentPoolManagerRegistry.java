package io.casehub.claudony.casehub.fleet;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AgentPoolManagerRegistry {

    private final Map<String, AgentSessionManager> managers = new ConcurrentHashMap<>();

    public void register(String poolName, AgentSessionManager manager) {
        managers.put(poolName, manager);
    }

    public Optional<AgentSessionManager> get(String poolName) {
        return Optional.ofNullable(managers.get(poolName));
    }

    public Set<String> poolNames() {
        return managers.keySet();
    }

    private final Map<String, String> sessionToPool = new ConcurrentHashMap<>();

    public void registerSession(String sessionId, String poolName) {
        sessionToPool.put(sessionId, poolName);
    }

    public void deregisterSession(String sessionId) {
        sessionToPool.remove(sessionId);
    }

    public Optional<String> poolNameForSession(String sessionId) {
        return Optional.ofNullable(sessionToPool.get(sessionId));
    }
}
