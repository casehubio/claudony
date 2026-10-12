package io.casehub.claudony.casehub.fleet;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.ProviderConfig;
import io.casehub.platform.api.model.ModelChain;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class EidosPoolConverter {

    private static final Logger LOG = Logger.getLogger(EidosPoolConverter.class);
    private static final String PROVIDER_NAME = "claudony";

    private EidosPoolConverter() {}

    public static AgentPoolDefinition toPoolDefinition(AgentDescriptor descriptor,
                                                        EidosPoolBridgeConfig defaults) {
        Map<String, String> config = findClaudonyProviderConfig(descriptor)
                .map(ProviderConfig::config)
                .orElse(Map.of());

        int minActive = parseIntOrDefault(config, "minActive", defaults.defaultMinActive());
        int maxActive = parseIntOrDefault(config, "maxActive", defaults.defaultMaxActive());
        String command = config.getOrDefault("command", defaults.defaultCommand());
        String workingDir = config.get("workingDir");

        ModelChain modelChain = resolveModelChain(descriptor);

        return AgentPoolDefinition.builder()
                .agent(descriptor.agentId())
                    .eidosAgentId(descriptor.agentId())
                    .command(command)
                    .workingDir(workingDir)
                    .modelChain(modelChain)
                .pool()
                    .minActive(minActive)
                    .maxActive(maxActive)
                .build();
    }

    public static Optional<ProviderConfig> findClaudonyProviderConfig(AgentDescriptor descriptor) {
        return descriptor.providerConfigs().stream()
                .filter(pc -> PROVIDER_NAME.equals(pc.providerName()))
                .findFirst();
    }

    private static ModelChain resolveModelChain(AgentDescriptor descriptor) {
        return descriptor.capabilities().stream()
                .filter(cap -> cap.model() != null || cap.modelRef() != null)
                .findFirst()
                .map(EidosPoolConverter::toModelChain)
                .orElse(null);
    }

    private static ModelChain toModelChain(AgentCapability capability) {
        if (capability.modelRef() != null) {
            return ModelChain.of(capability.modelRef());
        }
        return ModelChain.of(List.of(new ModelChain.ModelChainEntry.Queried(capability.model())));
    }

    private static int parseIntOrDefault(Map<String, String> config, String key, int defaultValue) {
        String value = config.get(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOG.warnf("Eidos pool bridge: invalid integer for '%s': '%s', using default %d",
                    key, value, defaultValue);
            return defaultValue;
        }
    }
}
