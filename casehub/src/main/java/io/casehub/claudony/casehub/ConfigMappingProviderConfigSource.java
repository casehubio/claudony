package io.casehub.claudony.casehub;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

@DefaultBean
@ApplicationScoped
public class ConfigMappingProviderConfigSource implements ProviderConfigSource {

    private final CaseHubConfig.Workers workers;

    @Inject
    public ConfigMappingProviderConfigSource(CaseHubConfig config) {
        this(config.workers());
    }

    ConfigMappingProviderConfigSource(CaseHubConfig.Workers workers) {
        this.workers = workers;
    }

    @Override
    public ClaudonyProviderConfig forAgent(String agentId) {
        var agentConfig = workers.providerConfig().get(agentId);
        if (agentConfig == null) return ClaudonyProviderConfig.EMPTY;
        return ClaudonyProviderConfig.fromConfigMapping(agentConfig);
    }

    @Override
    public Set<String> declaredAgentIds() {
        return Set.copyOf(workers.providerConfig().keySet());
    }
}
