package io.casehub.claudony.casehub;

import io.casehub.api.spi.ProvisionerConfigRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class CompositeProviderConfigSourceTest {

    @Test
    void forAgent_registryHasConfig_returnsRegistryConfig() {
        var registry = stubRegistry(Map.of("agent-a", Map.of("model", "opus")));
        var source = composite(registry, Map.of());

        var config = source.forAgent("agent-a");
        assertThat(config.model()).contains("opus");
    }

    @Test
    void forAgent_registryEmpty_fallsBackToConfigMapping() {
        var registry = stubRegistry(Map.of());
        var source = composite(registry, Map.of(
                "agent-b", stubAgentConfig("sonnet", Optional.of("high"))));

        var config = source.forAgent("agent-b");
        assertThat(config.model()).contains("sonnet");
        assertThat(config.effort()).contains("high");
    }

    @Test
    void forAgent_unknownAgent_returnsEmpty() {
        var source = composite(stubRegistry(Map.of()), Map.of());

        assertThat(source.forAgent("unknown")).isEqualTo(ClaudonyProviderConfig.EMPTY);
    }

    @Test
    void forAgent_registryDisplacesConfigMapping() {
        var registry = stubRegistry(Map.of("agent-a", Map.of("model", "opus")));
        var source = composite(registry, Map.of(
                "agent-a", stubAgentConfig("sonnet", Optional.of("low"))));

        var config = source.forAgent("agent-a");
        assertThat(config.model()).contains("opus");
        assertThat(config.effort()).isEmpty();
    }

    @Test
    void declaredAgentIds_unionOfBothSources() {
        var registry = stubRegistry(Map.of("registry-agent", Map.of()));
        var source = composite(registry, Map.of(
                "config-agent", stubAgentConfig("sonnet", Optional.empty())));

        assertThat(source.declaredAgentIds())
                .containsExactlyInAnyOrder("registry-agent", "config-agent");
    }

    @Test
    void declaredAgentIds_registryOnlyAgent_stillDiscovered() {
        var registry = stubRegistry(Map.of("ops-only", Map.of("model", "opus")));
        var source = composite(registry, Map.of());

        assertThat(source.declaredAgentIds()).containsExactly("ops-only");
    }

    @Test
    void noOpRegistry_behavesLikeConfigMappingOnly() {
        var noOp = stubRegistry(Map.of());
        var source = composite(noOp, Map.of(
                "agent-a", stubAgentConfig("opus", Optional.empty())));

        assertThat(source.forAgent("agent-a").model()).contains("opus");
        assertThat(source.declaredAgentIds()).containsExactly("agent-a");
    }

    // --- helpers ---

    private CompositeProviderConfigSource composite(ProvisionerConfigRegistry registry,
                                                      Map<String, CaseHubConfig.AgentProviderConfig> providerConfig) {
        return new CompositeProviderConfigSource(registry, stubWorkersConfig(providerConfig));
    }

    private ProvisionerConfigRegistry stubRegistry(Map<String, Map<String, Object>> data) {
        return new ProvisionerConfigRegistry() {
            @Override
            public Map<String, Object> configFor(String providerName, String agentId) {
                return data.getOrDefault(agentId, Map.of());
            }
            @Override
            public Set<String> declaredAgentIds(String providerName) {
                return data.keySet();
            }
        };
    }

    private CaseHubConfig.Workers stubWorkersConfig(Map<String, CaseHubConfig.AgentProviderConfig> providerConfig) {
        return new CaseHubConfig.Workers() {
            @Override public String defaultCommand() { return "claude"; }
            @Override public String defaultWorkingDir() { return "/tmp"; }
            @Override public Map<String, CaseHubConfig.AgentProviderConfig> providerConfig() { return providerConfig; }
        };
    }

    private CaseHubConfig.AgentProviderConfig stubAgentConfig(String model, Optional<String> effort) {
        return new CaseHubConfig.AgentProviderConfig() {
            @Override public Optional<String> command() { return Optional.empty(); }
            @Override public Optional<String> model() { return Optional.of(model); }
            @Override public Optional<String> appendSystemPrompt() { return Optional.empty(); }
            @Override public Optional<String> systemPrompt() { return Optional.empty(); }
            @Override public Optional<String> effort() { return effort; }
            @Override public Optional<String> permissionMode() { return Optional.empty(); }
            @Override public Optional<List<String>> tools() { return Optional.empty(); }
            @Override public Optional<List<String>> allowedTools() { return Optional.empty(); }
            @Override public Optional<List<String>> disallowedTools() { return Optional.empty(); }
            @Override public Optional<List<String>> addDirs() { return Optional.empty(); }
            @Override public Optional<String> workingDir() { return Optional.empty(); }
        };
    }
}
