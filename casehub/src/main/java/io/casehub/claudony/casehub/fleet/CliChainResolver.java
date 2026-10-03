package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.agent.router.ModelChainExhaustedException;
import io.casehub.platform.api.model.ModelChain;
import io.casehub.platform.api.model.ModelRegistry;

import java.util.Map;

public final class CliChainResolver {

    private CliChainResolver() {}

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
