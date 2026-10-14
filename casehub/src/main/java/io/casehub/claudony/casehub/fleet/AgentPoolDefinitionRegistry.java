package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.api.registry.RegistryService;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@jakarta.enterprise.context.ApplicationScoped
public class AgentPoolDefinitionRegistry {

    private final Map<PoolKey, AgentPoolDefinition> definitions = new ConcurrentHashMap<>();
    private final RegistryService                   registryService;

    @jakarta.inject.Inject
    public AgentPoolDefinitionRegistry(RegistryService registryService) {
        this.registryService = registryService;
    }

    public void register(AgentPoolDefinition definition) {
        register(definition, "fleet");
    }

    public void register(AgentPoolDefinition definition, String namespace) {
        register(definition, namespace, "default");
    }

    public void register(AgentPoolDefinition definition, String namespace, String tenancyId) {
        var key = new PoolKey(definition.agent().name(), tenancyId);
        var prev = definitions.putIfAbsent(key, definition);
        if (prev != null) {
            throw new IllegalStateException(
                    "Pool definition already registered for agent: " + PoolKey.poolName(key.agentName(), key.tenancyId()));
        }
        registryService.register(toRegistryEntry(definition, namespace));
    }

    public Optional<AgentPoolDefinition> get(String agentName) {
        return get(agentName, "default");
    }

    public Optional<AgentPoolDefinition> get(String agentName, String tenancyId) {
        return Optional.ofNullable(definitions.get(new PoolKey(agentName, tenancyId)));
    }

    public Collection<AgentPoolDefinition> all() {
        return all("default");
    }

    public Collection<AgentPoolDefinition> all(String tenancyId) {
        return definitions.entrySet().stream()
                          .filter(e -> e.getKey().tenancyId().equals(tenancyId))
                          .map(Map.Entry::getValue)
                          .toList();
    }

    public Set<String> names() {
        return names("default");
    }

    public Set<String> names(String tenancyId) {
        return definitions.keySet().stream()
                          .filter(k -> k.tenancyId().equals(tenancyId))
                          .map(PoolKey::agentName)
                          .collect(java.util.stream.Collectors.toSet());
    }

    public boolean isEmpty() {
        return definitions.isEmpty();
    }

    public int size() {
        return definitions.size();
    }

    public void updateScaling(String agentName, ScalingConfig newScaling) {
        updateScaling(agentName, "default", newScaling);
    }

    public void updateScaling(String agentName, String tenancyId, ScalingConfig newScaling) {
        var key = new PoolKey(agentName, tenancyId);
        definitions.compute(key, (k, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Pool not found: " + PoolKey.poolName(k.agentName(), k.tenancyId()));
            }
            var oldPool = existing.pool();
            var newPool = new AgentPoolDefinition.PoolConfig(oldPool.minActive(), oldPool.maxActive(), oldPool.eviction(), newScaling, oldPool.budget());
            return new AgentPoolDefinition(existing.agent(), newPool);
        });
        var updated = definitions.get(key);
        var ns      = registryService.resolve(agentName).map(RegistryEntry::namespace).orElse("fleet");
        registryService.register(toRegistryEntry(updated, ns));
    }

    public void updateCapacity(String agentName, int minActive, int maxActive) {
        updateCapacity(agentName, "default", minActive, maxActive);
    }

    public void updateCapacity(String agentName, String tenancyId, int minActive, int maxActive) {
        var key = new PoolKey(agentName, tenancyId);
        definitions.compute(key, (k, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Pool not found: " + PoolKey.poolName(k.agentName(), k.tenancyId()));
            }
            var oldPool = existing.pool();
            var newPool = new AgentPoolDefinition.PoolConfig(minActive, maxActive, oldPool.eviction(), oldPool.scaling(), oldPool.budget());
            return new AgentPoolDefinition(existing.agent(), newPool);
        });
        var updated = definitions.get(key);
        var ns      = registryService.resolve(agentName).map(RegistryEntry::namespace).orElse("fleet");
        registryService.register(toRegistryEntry(updated, ns));
    }

    public void updateBudget(String agentName, BudgetConfig newBudget) {
        updateBudget(agentName, "default", newBudget);
    }

    public void updateBudget(String agentName, String tenancyId, BudgetConfig newBudget) {
        var key = new PoolKey(agentName, tenancyId);
        definitions.compute(key, (k, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Pool not found: " + PoolKey.poolName(k.agentName(), k.tenancyId()));
            }
            var oldPool = existing.pool();
            var newPool = new AgentPoolDefinition.PoolConfig(oldPool.minActive(), oldPool.maxActive(), oldPool.eviction(), oldPool.scaling(), newBudget);
            return new AgentPoolDefinition(existing.agent(), newPool);
        });
        var updated = definitions.get(key);
        var ns      = registryService.resolve(agentName).map(RegistryEntry::namespace).orElse("fleet");
        registryService.register(toRegistryEntry(updated, ns));
    }

    public void updateAgentConfig(String agentName, AgentPoolDefinition.AgentConfig newAgentConfig) {
        updateAgentConfig(agentName, "default", newAgentConfig);
    }

    public void updateAgentConfig(String agentName, String tenancyId, AgentPoolDefinition.AgentConfig newAgentConfig) {
        var key = new PoolKey(agentName, tenancyId);
        definitions.compute(key, (k, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Pool not found: " + PoolKey.poolName(k.agentName(), k.tenancyId()));
            }
            return new AgentPoolDefinition(newAgentConfig, existing.pool());
        });
        var updated = definitions.get(key);
        var ns      = registryService.resolve(agentName).map(RegistryEntry::namespace).orElse("fleet");
        registryService.register(toRegistryEntry(updated, ns));
    }

    private RegistryEntry toRegistryEntry(AgentPoolDefinition def, String namespace) {
        var now = java.time.Instant.now();
        return new RegistryEntry(
                def.agent().name(), "pool", namespace, "default",
                java.util.Map.of(
                        "agentName", def.agent().name(),
                        "minActive", String.valueOf(def.pool().minActive()),
                        "maxActive", String.valueOf(def.pool().maxActive())
                                ),
                now, now, java.time.Duration.ofHours(24), HealthStatus.HEALTHY, null);
    }
}
