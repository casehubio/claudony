package io.casehub.claudony.casehub;

import io.casehub.api.spi.ProvisionerConfigRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CompositeProviderConfigSource implements ProviderConfigSource {

    private static final Logger LOG = Logger.getLogger(CompositeProviderConfigSource.class);
    private static final String PROVIDER_NAME = "claudony";

    private final ProvisionerConfigRegistry registry;
    private final CaseHubConfig.Workers workers;

    @Inject
    public CompositeProviderConfigSource(ProvisionerConfigRegistry registry,
                                          CaseHubConfig config) {
        this(registry, config.workers());
    }

    CompositeProviderConfigSource(ProvisionerConfigRegistry registry,
                                    CaseHubConfig.Workers workers) {
        this.registry = registry;
        this.workers = workers;
    }

    @Override
    public ClaudonyProviderConfig forAgent(String agentId) {
        Map<String, Object> registryConfig = registry.configFor(PROVIDER_NAME, agentId);
        if (!registryConfig.isEmpty()) {
            if (workers.providerConfig().containsKey(agentId)) {
                LOG.warnf("Agent '%s': registry config displaces application.properties config entirely"
                        + " (no per-field merge — registry is authoritative when present)", agentId);
            }
            return ClaudonyProviderConfig.fromMap(registryConfig);
        }
        var agentConfig = workers.providerConfig().get(agentId);
        return agentConfig == null ? ClaudonyProviderConfig.EMPTY
                                   : ClaudonyProviderConfig.fromConfigMapping(agentConfig);
    }

    @Override
    public Set<String> declaredAgentIds() {
        var all = new HashSet<>(registry.declaredAgentIds(PROVIDER_NAME));
        all.addAll(workers.providerConfig().keySet());
        return Set.copyOf(all);
    }
}
