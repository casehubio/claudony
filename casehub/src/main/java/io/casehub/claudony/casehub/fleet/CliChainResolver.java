package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.agent.router.ModelChainExhaustedException;
import io.casehub.platform.api.model.ModelChain;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelRegistry;
import io.casehub.platform.api.model.ModelTier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class CliChainResolver {

    private CliChainResolver() {}

    // CLI trusts YAML-configured model names — runtime circuit breaker handles actual failures
    public static final ModelRegistry CLI_PASS_THROUGH = new ModelRegistry() {
        private static final ModelDescriptor PLACEHOLDER = new ModelDescriptor(
                "cli", "cli", "cli", null, "cli", "cli", "cli",
                ModelTier.STANDARD, Set.of(), 0, 0, ModelLocality.CLOUD, null, null, Map.of());

        @Override
        public Optional<ModelDescriptor> resolveById(String modelId) {
            return Optional.of(PLACEHOLDER);
        }

        @Override
        public List<ModelDescriptor> query(ModelQuery query) {
            return List.of();
        }

        @Override
        public List<ModelDescriptor> all() {
            return List.of();
        }
    };

    public record CliResolvedModel(String model, String command) {}

    public static CliResolvedModel resolve(ModelChain chain, String defaultCommand,
                                           Map<String, String> entryCommands,
                                           ModelRegistry modelRegistry) {
        if (chain == null || chain.isEmpty()) {
            return new CliResolvedModel(null, defaultCommand);
        }

        for (var entry : chain.entries()) {
            String modelRef = switch (entry) {
                case ModelChain.ModelChainEntry.Named n -> {
                    var desc = modelRegistry.resolveById(n.modelRef());
                    yield desc.isPresent() ? n.modelRef() : null;
                }
                case ModelChain.ModelChainEntry.Queried q -> {
                    var candidates = modelRegistry.query(q.query());
                    yield candidates.isEmpty() ? null : candidates.get(0).apiModelId();
                }
            };

            if (modelRef == null) continue;

            var command = entryCommands.getOrDefault(modelRef, defaultCommand);
            return new CliResolvedModel(modelRef, command);
        }

        throw new ModelChainExhaustedException(chain, chain.entries());
    }
}
