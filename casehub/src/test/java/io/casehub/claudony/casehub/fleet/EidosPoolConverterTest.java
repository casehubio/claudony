package io.casehub.claudony.casehub.fleet;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.ProviderConfig;
import io.casehub.platform.api.model.ModelQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EidosPoolConverterTest {

    private static final EidosPoolBridgeConfig DEFAULTS = EidosPoolBridgeConfig.DEFAULTS;

    @Test
    void fullDescriptorWithCapabilitiesAndPoolConfig() {
        var descriptor = AgentDescriptor.builder()
                .agentId("code-reviewer")
                .name("Code Reviewer")
                .slot("reviewer")
                .tenancyId("default")
                .capabilities(List.of(
                        AgentCapability.builder()
                                .name("review")
                                .model(ModelQuery.builder().family("claude").build())
                                .build()))
                .providerConfigs(List.of(
                        new ProviderConfig("claudony", Map.of(
                                "minActive", "2",
                                "maxActive", "10",
                                "command", "claude --model opus"))))
                .build();

        var result = EidosPoolConverter.toPoolDefinition(descriptor, DEFAULTS);

        assertThat(result.agent().name()).isEqualTo("code-reviewer");
        assertThat(result.agent().eidosAgentId()).isEqualTo("code-reviewer");
        assertThat(result.agent().command()).isEqualTo("claude --model opus");
        assertThat(result.pool().minActive()).isEqualTo(2);
        assertThat(result.pool().maxActive()).isEqualTo(10);
    }

    @Test
    void missingPoolConfigUsesDefaults() {
        var descriptor = AgentDescriptor.builder()
                .agentId("researcher")
                .name("Researcher")
                .slot("researcher")
                .tenancyId("default")
                .providerConfigs(List.of(
                        new ProviderConfig("claudony", Map.of())))
                .build();

        var result = EidosPoolConverter.toPoolDefinition(descriptor, DEFAULTS);

        assertThat(result.pool().minActive()).isEqualTo(0);
        assertThat(result.pool().maxActive()).isEqualTo(5);
        assertThat(result.agent().command()).isEqualTo("claude");
    }

    @Test
    void noModelOnAnyCapability() {
        var descriptor = AgentDescriptor.builder()
                .agentId("worker")
                .name("Worker")
                .slot("worker")
                .tenancyId("default")
                .providerConfigs(List.of(
                        new ProviderConfig("claudony", Map.of())))
                .build();

        var result = EidosPoolConverter.toPoolDefinition(descriptor, DEFAULTS);

        assertThat(result.agent().modelChain()).isNull();
    }

    @Test
    void multipleCapabilitiesFirstWithModelWins() {
        var descriptor = AgentDescriptor.builder()
                .agentId("multi")
                .name("Multi")
                .slot("multi")
                .tenancyId("default")
                .capabilities(List.of(
                        AgentCapability.builder().name("no-model").build(),
                        AgentCapability.builder()
                                .name("has-model")
                                .model(ModelQuery.builder().family("claude").build())
                                .build()))
                .providerConfigs(List.of(
                        new ProviderConfig("claudony", Map.of())))
                .build();

        var result = EidosPoolConverter.toPoolDefinition(descriptor, DEFAULTS);

        assertThat(result.agent().modelChain()).isNotNull();
        assertThat(result.agent().modelChain().entries()).hasSize(1);
    }

    @Test
    void findClaudonyProviderConfigPresent() {
        var descriptor = AgentDescriptor.builder()
                .agentId("test")
                .name("Test")
                .slot("test")
                .tenancyId("default")
                .providerConfigs(List.of(
                        new ProviderConfig("other", Map.of()),
                        new ProviderConfig("claudony", Map.of("command", "claude"))))
                .build();

        var result = EidosPoolConverter.findClaudonyProviderConfig(descriptor);

        assertThat(result).isPresent();
        assertThat(result.get().providerName()).isEqualTo("claudony");
    }

    @Test
    void findClaudonyProviderConfigAbsent() {
        var descriptor = AgentDescriptor.builder()
                .agentId("test")
                .name("Test")
                .slot("test")
                .tenancyId("default")
                .providerConfigs(List.of(
                        new ProviderConfig("openai", Map.of())))
                .build();

        assertThat(EidosPoolConverter.findClaudonyProviderConfig(descriptor)).isEmpty();
    }

    @Test
    void malformedMinActiveUsesDefault() {
        var descriptor = AgentDescriptor.builder()
                .agentId("bad")
                .name("Bad")
                .slot("bad")
                .tenancyId("default")
                .providerConfigs(List.of(
                        new ProviderConfig("claudony", Map.of("minActive", "not-a-number"))))
                .build();

        var result = EidosPoolConverter.toPoolDefinition(descriptor, DEFAULTS);

        assertThat(result.pool().minActive()).isEqualTo(0);
    }

    @Test
    void workingDirFromProviderConfig() {
        var descriptor = AgentDescriptor.builder()
                .agentId("test")
                .name("Test")
                .slot("test")
                .tenancyId("default")
                .providerConfigs(List.of(
                        new ProviderConfig("claudony", Map.of("workingDir", "/workspace/reviews"))))
                .build();

        var result = EidosPoolConverter.toPoolDefinition(descriptor, DEFAULTS);

        assertThat(result.agent().workingDir()).isEqualTo("/workspace/reviews");
    }
}
