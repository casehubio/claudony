package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.agent.router.ModelChainExhaustedException;
import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelChain;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelRegistry;
import io.casehub.platform.api.model.ModelTier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliChainResolverTest {

    private ModelRegistry registryWith(String... modelIds) {
        return new ModelRegistry() {
            @Override public Optional<ModelDescriptor> resolveById(String id) {
                return Arrays.stream(modelIds).filter(m -> m.equals(id)).findFirst()
                        .map(m -> new ModelDescriptor(m, m, "claude", "default",
                                "anthropic", "claude", m, ModelTier.FLAGSHIP,
                                Set.of(), 200000, 16000, ModelLocality.CLOUD, CostTier.HIGH, null, Map.of()));
            }
            @Override public List<ModelDescriptor> query(ModelQuery query) {
                return all().stream().filter(d -> query.tier() == null || d.tier() == query.tier()).toList();
            }
            @Override public List<ModelDescriptor> all() {
                return Arrays.stream(modelIds).map(m -> resolveById(m).orElseThrow()).toList();
            }
        };
    }

    @Test
    void sameBackendChain_resolvesFirstAvailable() {
        var result = CliChainResolver.resolve(
                ModelChain.of("opus", "sonnet"), "claude", Map.of(), registryWith("opus", "sonnet"));
        assertThat(result.model()).isEqualTo("opus");
        assertThat(result.command()).isEqualTo("claude");
    }

    @Test
    void firstMissing_fallsToSecond() {
        var result = CliChainResolver.resolve(
                ModelChain.of("opus", "sonnet"), "claude", Map.of(), registryWith("sonnet"));
        assertThat(result.model()).isEqualTo("sonnet");
    }

    @Test
    void crossBackend_usesCommandOverride() {
        var commands = Map.of("llama3", "ollama run");
        var result = CliChainResolver.resolve(
                ModelChain.of("llama3"), "claude", commands, registryWith("llama3"));
        assertThat(result.model()).isEqualTo("llama3");
        assertThat(result.command()).isEqualTo("ollama run");
    }

    @Test
    void allExhausted_throws() {
        assertThatThrownBy(() -> CliChainResolver.resolve(
                ModelChain.of("opus"), "claude", Map.of(), registryWith()))
                .isInstanceOf(ModelChainExhaustedException.class);
    }

    @Test
    void nullChain_returnsNullModel() {
        var result = CliChainResolver.resolve(null, "claude", Map.of(), registryWith());
        assertThat(result.model()).isNull();
        assertThat(result.command()).isEqualTo("claude");
    }

    @Test
    void queriedEntry_resolvesViaTier() {
        var entries = List.<ModelChain.ModelChainEntry>of(
                new ModelChain.ModelChainEntry.Queried(ModelQuery.builder().tier(ModelTier.FLAGSHIP).build()));
        var result = CliChainResolver.resolve(
                ModelChain.of(entries), "claude", Map.of(), registryWith("opus"));
        assertThat(result.model()).isEqualTo("opus");
    }
}
