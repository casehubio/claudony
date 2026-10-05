package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.api.model.ModelRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FleetPoolChainIntegrationTest {

    private final AgentPoolYamlParser parser = new AgentPoolYamlParser();

    private ModelRegistry registryWith(String... modelIds) {
        return TestModelRegistries.withIds(modelIds);
    }

    @Test
    void fullChain_yamlToResolution() {
        var yaml = """
                agent-pools:
                  reviewer:
                    model-chain:
                      - opus
                      - sonnet
                      - haiku
                    command: claude
                    pool:
                      max-active: 5
                """;

        var defs = parser.parse(yaml);
        assertThat(defs).hasSize(1);
        var def = defs.get(0);

        var result = CliChainResolver.resolve(
                def.agent().modelChain(),
                def.agent().command(),
                def.agent().entryCommands(),
                registryWith("opus", "sonnet", "haiku"));

        assertThat(result.model()).isEqualTo("opus");
        assertThat(result.command()).isEqualTo("claude");
    }

    @Test
    void fallback_firstMissing_yamlToResolution() {
        var yaml = """
                agent-pools:
                  reviewer:
                    model-chain:
                      - opus
                      - sonnet
                    command: claude
                    pool:
                      max-active: 5
                """;

        var defs = parser.parse(yaml);
        var def = defs.get(0);

        var result = CliChainResolver.resolve(
                def.agent().modelChain(),
                def.agent().command(),
                def.agent().entryCommands(),
                registryWith("sonnet"));

        assertThat(result.model()).isEqualTo("sonnet");
    }

    @Test
    void crossBackend_yamlToResolution() {
        var yaml = """
                agent-pools:
                  reviewer:
                    model-chain:
                      - opus
                      - model: llama3
                        command: ollama run
                    command: claude
                    pool:
                      max-active: 5
                """;

        var defs = parser.parse(yaml);
        var def = defs.get(0);

        var result = CliChainResolver.resolve(
                def.agent().modelChain(),
                def.agent().command(),
                def.agent().entryCommands(),
                registryWith("llama3"));

        assertThat(result.model()).isEqualTo("llama3");
        assertThat(result.command()).isEqualTo("ollama run");
    }

    @Test
    void queriedEntry_yamlToResolution() {
        var yaml = """
                agent-pools:
                  reviewer:
                    model-chain:
                      - tier: EMBEDDING
                      - tier: FLAGSHIP
                    command: claude
                    pool:
                      max-active: 5
                """;

        var defs = parser.parse(yaml);
        var def = defs.get(0);

        var result = CliChainResolver.resolve(
                def.agent().modelChain(),
                def.agent().command(),
                def.agent().entryCommands(),
                registryWith("opus"));

        assertThat(result.model()).isEqualTo("opus");
    }

    @Test
    void noChain_defaultBehavior() {
        var yaml = """
                agent-pools:
                  reviewer:
                    command: claude
                    pool:
                      max-active: 5
                """;

        var defs = parser.parse(yaml);
        var def = defs.get(0);

        assertThat(def.agent().modelChain()).isNull();

        var result = CliChainResolver.resolve(
                def.agent().modelChain(),
                def.agent().command(),
                def.agent().entryCommands(),
                registryWith());

        assertThat(result.model()).isNull();
        assertThat(result.command()).isEqualTo("claude");
    }

    @Test
    void gracePeriod_parsedFromYaml() {
        var yaml = """
                agent-pools:
                  reviewer:
                    model-chain:
                      - model: llama3
                        command: ollama run
                        grace-period: 90s
                    command: claude
                    pool:
                      max-active: 5
                """;

        var defs = parser.parse(yaml);
        var def = defs.get(0);

        assertThat(def.agent().gracePeriods())
                .containsEntry("llama3", java.time.Duration.ofSeconds(90));
    }

    @Test
    void modelFallbackEvent_constructedCorrectly() {
        var event = new ModelFallbackEvent("reviewer", "opus", "sonnet", 2);
        assertThat(event.poolName()).isEqualTo("reviewer");
        assertThat(event.requestedModel()).isEqualTo("opus");
        assertThat(event.resolvedModel()).isEqualTo("sonnet");
        assertThat(event.fallbackDepth()).isEqualTo(2);
    }
}
