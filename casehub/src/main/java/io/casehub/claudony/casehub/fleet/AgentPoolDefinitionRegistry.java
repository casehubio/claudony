package io.casehub.claudony.casehub.fleet;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@jakarta.enterprise.context.ApplicationScoped
public class AgentPoolDefinitionRegistry {

    private final Map<String, AgentPoolDefinition> definitions = new ConcurrentHashMap<>();

    public void register(AgentPoolDefinition definition) {
        var prev = definitions.putIfAbsent(definition.agent().name(), definition);
        if (prev != null) {
            throw new IllegalStateException(
                    "Pool definition already registered for agent: " + definition.agent().name());
        }
    }

    public Optional<AgentPoolDefinition> get(String agentName) {
        return Optional.ofNullable(definitions.get(agentName));
    }

    public Collection<AgentPoolDefinition> all() {
        return java.util.List.copyOf(definitions.values());
    }

    public Set<String> names() {
        return Set.copyOf(definitions.keySet());
    }

    public boolean isEmpty() {
        return definitions.isEmpty();
    }

    public int size() {
        return definitions.size();
    }

    public void updateScaling(String agentName, ScalingConfig newScaling) {
        definitions.compute(agentName, (k, existing) -> {
            if (existing == null) {throw new IllegalArgumentException("Pool not found: " + k);}
            var oldPool = existing.pool();
            var newPool = new AgentPoolDefinition.PoolConfig(oldPool.minActive(), oldPool.maxActive(), oldPool.eviction(), newScaling, oldPool.budget());
            return new AgentPoolDefinition(existing.agent(), newPool);
        });
    }

    public void updateCapacity(String agentName, int minActive, int maxActive) {
        definitions.compute(agentName, (k, existing) -> {
            if (existing == null) {throw new IllegalArgumentException("Pool not found: " + k);}
            var oldPool = existing.pool();
            var newPool = new AgentPoolDefinition.PoolConfig(minActive, maxActive, oldPool.eviction(), oldPool.scaling(), oldPool.budget());
            return new AgentPoolDefinition(existing.agent(), newPool);
        });
    }

    public void updateBudget(String agentName, BudgetConfig newBudget) {
        definitions.compute(agentName, (k, existing) -> {
            if (existing == null) {throw new IllegalArgumentException("Pool not found: " + k);}
            var oldPool = existing.pool();
            var newPool = new AgentPoolDefinition.PoolConfig(oldPool.minActive(), oldPool.maxActive(), oldPool.eviction(), oldPool.scaling(), newBudget);
            return new AgentPoolDefinition(existing.agent(), newPool);
        });
    }

}
