package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPoolYamlParserTest {

    private AgentPoolYamlParser parser;

    @BeforeEach
    void setUp() {
        parser = new AgentPoolYamlParser();
    }

    @Test
    void fullYamlProducesDefinition() {
        var yaml = """
                agent-pools:
                  code-reviewer:
                    working-dir: /workspace/reviews
                    policy: SHARED_READ
                    command: claude --model opus
                    pool:
                      min-active: 2
                      max-active: 10
                      eviction: memory-weighted
                """;

        var defs = parser.parse(yaml);
        assertThat(defs).hasSize(1);

        var def = defs.getFirst();
        assertThat(def.agent().name()).isEqualTo("code-reviewer");
        assertThat(def.agent().workingDir()).isEqualTo("/workspace/reviews");
        assertThat(def.agent().policy()).isEqualTo(WorkingDirPolicy.SHARED_READ);
        assertThat(def.agent().command()).isEqualTo("claude --model opus");
        assertThat(def.pool().minActive()).isEqualTo(2);
        assertThat(def.pool().maxActive()).isEqualTo(10);
        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.MEMORY_WEIGHTED);
    }

    @Test
    void minimalYamlUsesDefaults() {
        var yaml = """
                agent-pools:
                  researcher: {}
                """;

        var defs = parser.parse(yaml);
        assertThat(defs).hasSize(1);

        var def = defs.getFirst();
        assertThat(def.agent().name()).isEqualTo("researcher");
        assertThat(def.agent().workingDir()).isNull();
        assertThat(def.agent().policy()).isEqualTo(WorkingDirPolicy.EXCLUSIVE);
        assertThat(def.agent().command()).isNull();
        assertThat(def.pool().minActive()).isZero();
        assertThat(def.pool().maxActive()).isEqualTo(10);
        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.MEMORY_WEIGHTED);
    }

    @Test
    void multiplePoolDefinitions() {
        var yaml = """
                agent-pools:
                  reviewer:
                    working-dir: /reviews
                    pool:
                      max-active: 3
                  coder:
                    command: claude --model sonnet
                    pool:
                      min-active: 1
                      max-active: 8
                """;

        var defs = parser.parse(yaml);
        assertThat(defs).hasSize(2);
        assertThat(defs).extracting(d -> d.agent().name())
                .containsExactlyInAnyOrder("reviewer", "coder");
    }

    @Test
    void lruEvictionStrategy() {
        var yaml = """
                agent-pools:
                  worker:
                    pool:
                      eviction: lru
                """;

        var def = parser.parse(yaml).getFirst();
        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.LRU);
    }

    @Test
    void poolSectionOptional() {
        var yaml = """
                agent-pools:
                  simple-agent:
                    working-dir: /work
                    command: claude
                """;

        var def = parser.parse(yaml).getFirst();
        assertThat(def.agent().name()).isEqualTo("simple-agent");
        assertThat(def.agent().workingDir()).isEqualTo("/work");
        assertThat(def.pool().minActive()).isZero();
        assertThat(def.pool().maxActive()).isEqualTo(10);
    }

    @Test
    void emptyYamlProducesEmptyList() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("---")).isEmpty();
    }

    @Test
    void noAgentPoolsKeyProducesEmptyList() {
        var yaml = """
                other-config:
                  key: value
                """;
        assertThat(parser.parse(yaml)).isEmpty();
    }

    @Test
    void parseIntoRegistersAll() {
        var yaml = """
                agent-pools:
                  alpha:
                    pool:
                      max-active: 5
                  beta: {}
                """;

        var registry = new AgentPoolDefinitionRegistry();
        parser.parseInto(yaml, registry);

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.get("alpha")).isPresent();
        assertThat(registry.get("beta")).isPresent();
    }

    @Test
    void yamlProducesSameResultAsBuilder() {
        var yaml = """
                agent-pools:
                  code-reviewer:
                    working-dir: /workspace/reviews
                    policy: SHARED_READ
                    command: claude --model opus
                    pool:
                      min-active: 2
                      max-active: 10
                      eviction: memory-weighted
                """;

        var fromYaml = parser.parse(yaml).getFirst();

        var fromBuilder = AgentPoolDefinition.builder()
                .agent("code-reviewer")
                    .workingDir("/workspace/reviews")
                    .policy(WorkingDirPolicy.SHARED_READ)
                    .command("claude --model opus")
                .pool()
                    .minActive(2)
                    .maxActive(10)
                    .eviction(EvictionStrategy.MEMORY_WEIGHTED)
                .build();

        assertThat(fromYaml.agent()).isEqualTo(fromBuilder.agent());
        assertThat(fromYaml.pool()).isEqualTo(fromBuilder.pool());
    }

    @Test
    void caseInsensitivePolicy() {
        var yaml = """
                agent-pools:
                  worker:
                    policy: shared_read
                """;

        var def = parser.parse(yaml).getFirst();
        assertThat(def.agent().policy()).isEqualTo(WorkingDirPolicy.SHARED_READ);
    }

    @Test
    void caseInsensitiveEviction() {
        var yaml = """
                agent-pools:
                  worker:
                    pool:
                      eviction: MEMORY-WEIGHTED
                """;

        var def = parser.parse(yaml).getFirst();
        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.MEMORY_WEIGHTED);
    }
}
