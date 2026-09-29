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
}
