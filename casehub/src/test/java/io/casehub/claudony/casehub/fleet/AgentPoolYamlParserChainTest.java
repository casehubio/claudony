package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.api.model.ModelChain;
import io.casehub.platform.api.model.ModelTier;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPoolYamlParserChainTest {

    private final AgentPoolYamlParser parser = new AgentPoolYamlParser();

    @Test
    void stringEntries_parsedAsNamed() {
        var yaml = """
                agent-pools:
                  reviewer:
                    model-chain:
                      - opus
                      - sonnet
                    pool:
                      max-active: 3
                """;
        var defs = parser.parse(yaml);
        assertThat(defs).hasSize(1);
        var chain = defs.get(0).agent().modelChain();
        assertThat(chain).isNotNull();
        assertThat(chain.entries()).hasSize(2);
        assertThat(chain.entries().get(0)).isInstanceOf(ModelChain.ModelChainEntry.Named.class);
        assertThat(((ModelChain.ModelChainEntry.Named) chain.entries().get(0)).modelRef()).isEqualTo("opus");
        assertThat(((ModelChain.ModelChainEntry.Named) chain.entries().get(1)).modelRef()).isEqualTo("sonnet");
    }

    @Test
    void structuredEntry_withCommandOverride() {
        var yaml = """
                agent-pools:
                  reviewer:
                    model-chain:
                      - model: llama3
                        command: ollama run
                    pool:
                      max-active: 3
                """;
        var defs = parser.parse(yaml);
        var agent = defs.get(0).agent();
        assertThat(agent.entryCommands()).containsEntry("llama3", "ollama run");
    }

    @Test
    void queriedEntry_withTier() {
        var yaml = """
                agent-pools:
                  reviewer:
                    model-chain:
                      - tier: FLAGSHIP
                        vendor: google
                    pool:
                      max-active: 3
                """;
        var defs = parser.parse(yaml);
        var entry = defs.get(0).agent().modelChain().entries().get(0);
        assertThat(entry).isInstanceOf(ModelChain.ModelChainEntry.Queried.class);
        var queried = (ModelChain.ModelChainEntry.Queried) entry;
        assertThat(queried.query().tier()).isEqualTo(ModelTier.FLAGSHIP);
        assertThat(queried.query().vendor()).isEqualTo("google");
    }

    @Test
    void gracePeriodOverride() {
        var yaml = """
                agent-pools:
                  reviewer:
                    model-chain:
                      - model: llama3
                        command: ollama run
                        grace-period: 60s
                    pool:
                      max-active: 3
                """;
        var defs = parser.parse(yaml);
        assertThat(defs.get(0).agent().gracePeriods())
                .containsEntry("llama3", Duration.ofSeconds(60));
    }

    @Test
    void noModelChain_nullChain() {
        var yaml = """
                agent-pools:
                  reviewer:
                    command: claude
                    pool:
                      max-active: 3
                """;
        var defs = parser.parse(yaml);
        assertThat(defs.get(0).agent().modelChain()).isNull();
    }

    @Test
    void mixedEntries() {
        var yaml = """
                agent-pools:
                  reviewer:
                    model-chain:
                      - opus
                      - model: sonnet
                      - tier: STANDARD
                    pool:
                      max-active: 3
                """;
        var defs = parser.parse(yaml);
        var chain = defs.get(0).agent().modelChain();
        assertThat(chain.entries()).hasSize(3);
        assertThat(chain.entries().get(0)).isInstanceOf(ModelChain.ModelChainEntry.Named.class);
        assertThat(chain.entries().get(1)).isInstanceOf(ModelChain.ModelChainEntry.Named.class);
        assertThat(chain.entries().get(2)).isInstanceOf(ModelChain.ModelChainEntry.Queried.class);
    }
}
