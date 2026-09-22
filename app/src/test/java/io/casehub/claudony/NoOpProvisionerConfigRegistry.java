package io.casehub.claudony;

import io.casehub.api.spi.ProvisionerConfigRegistry;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@DefaultBean
public class NoOpProvisionerConfigRegistry implements ProvisionerConfigRegistry {
    @Override
    public Map<String, Object> configFor(String providerName, String agentId) {
        return Map.of();
    }

    @Override
    public Set<String> declaredAgentIds(String providerName) {
        return Set.of();
    }
}