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

    private final Map<String, AgentPoolDefinition> definitions = new ConcurrentHashMap<>();
    private final RegistryService                  registryService;


    @jakarta.inject.Inject
    public AgentPoolDefinitionRegistry(RegistryService registryService) {
        this.registryService = registryService;
    }

    public void register(AgentPoolDefinition definition) {
        register(definition, "fleet");
    }

    public void register(AgentPoolDefinition definition, String namespace) {
        var prev = definitions.putIfAbsent(definition.agent().name(), definition);
        if (prev != null) {
            throw new IllegalStateException(
                    "Pool definition already registered for agent: " + definition.agent().name());
        }
        registryService.register(toRegistryEntry(definition, namespace));
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
        var updated = definitions.get(agentName);
        var ns      = registryService.resolve(agentName).map(RegistryEntry::namespace).orElse("fleet");
        registryService.register(toRegistryEntry(updated, ns));
    }

    public void updateCapacity(String agentName, int minActive, int maxActive) {
        definitions.compute(agentName, (k, existing) -> {
            if (existing == null) {throw new IllegalArgumentException("Pool not found: " + k);}
            var oldPool = existing.pool();
            var newPool = new AgentPoolDefinition.PoolConfig(minActive, maxActive, oldPool.eviction(), oldPool.scaling(), oldPool.budget());
            return new AgentPoolDefinition(existing.agent(), newPool);
        });
        var updated = definitions.get(agentName);
        var ns      = registryService.resolve(agentName).map(RegistryEntry::namespace).orElse("fleet");
        registryService.register(toRegistryEntry(updated, ns));
    }

    public void updateBudget(String agentName, BudgetConfig newBudget) {
        definitions.compute(agentName, (k, existing) -> {
            if (existing == null) {throw new IllegalArgumentException("Pool not found: " + k);}
            var oldPool = existing.pool();
            var newPool = new AgentPoolDefinition.PoolConfig(oldPool.minActive(), oldPool.maxActive(), oldPool.eviction(), oldPool.scaling(), newBudget);
            return new AgentPoolDefinition(existing.agent(), newPool);
        });
        var updated = definitions.get(agentName);
        var ns      = registryService.resolve(agentName).map(RegistryEntry::namespace).orElse("fleet");
        registryService.register(toRegistryEntry(updated, ns));
    }

    public void updateAgentConfig(String agentName, AgentPoolDefinition.AgentConfig newAgentConfig) {
        definitions.compute(agentName, (k, existing) -> {
            if (existing == null) {throw new IllegalArgumentException("Pool not found: " + k);}
            return new AgentPoolDefinition(newAgentConfig, existing.pool());
        });
        var updated = definitions.get(agentName);
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
