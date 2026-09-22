package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPoolDefinitionTest {

    @Test
    void fullBuilderProducesDefinition() {
        var def = AgentPoolDefinition.builder()
                .agent("code-reviewer")
                    .workingDir("/workspace/reviews")
                    .policy(WorkingDirPolicy.SHARED_READ)
                    .command("claude --model opus")
                .pool()
                    .minActive(2)
                    .maxActive(10)
                    .eviction(EvictionStrategy.MEMORY_WEIGHTED)
                .build();

        assertThat(def.agent().name()).isEqualTo("code-reviewer");
        assertThat(def.agent().workingDir()).isEqualTo("/workspace/reviews");
        assertThat(def.agent().policy()).isEqualTo(WorkingDirPolicy.SHARED_READ);
        assertThat(def.agent().command()).isEqualTo("claude --model opus");
        assertThat(def.pool().minActive()).isEqualTo(2);
        assertThat(def.pool().maxActive()).isEqualTo(10);
        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.MEMORY_WEIGHTED);
    }

    @Test
    void defaultsApplied() {
        var def = AgentPoolDefinition.builder()
                .agent("researcher")
                .build();

        assertThat(def.agent().name()).isEqualTo("researcher");
        assertThat(def.agent().workingDir()).isNull();
        assertThat(def.agent().policy()).isEqualTo(WorkingDirPolicy.EXCLUSIVE);
        assertThat(def.agent().command()).isNull();
        assertThat(def.pool().minActive()).isZero();
        assertThat(def.pool().maxActive()).isEqualTo(10);
        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.MEMORY_WEIGHTED);
    }

    @Test
    void evictionStrategyOverride() {
        var def = AgentPoolDefinition.builder()
                .agent("worker")
                .pool()
                    .eviction(EvictionStrategy.LRU)
                .build();

        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.LRU);
    }

    @Test
    void poolOnlyOverridesDefaults() {
        var def = AgentPoolDefinition.builder()
                .agent("coder")
                .pool()
                    .maxActive(5)
                .build();

        assertThat(def.agent().name()).isEqualTo("coder");
        assertThat(def.pool().minActive()).isZero();
        assertThat(def.pool().maxActive()).isEqualTo(5);
    }

    @Test
    void agentNameRequired() {
        assertThatThrownBy(() -> AgentPoolDefinition.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("agent name");
    }

    @Test
    void agentNameCannotBeBlank() {
        assertThatThrownBy(() -> AgentPoolDefinition.builder().agent("  ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent name");
    }

    @Test
    void maxActiveMustBePositive() {
        assertThatThrownBy(() -> AgentPoolDefinition.builder()
                .agent("test")
                .pool().maxActive(0)
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxActiveMustBeGreaterOrEqualToMinActive() {
        assertThatThrownBy(() -> AgentPoolDefinition.builder()
                .agent("test")
                .pool().minActive(5).maxActive(3)
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minActiveCannotBeNegative() {
        assertThatThrownBy(() -> AgentPoolDefinition.builder()
                .agent("test")
                .pool().minActive(-1)
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toSessionManagerConfig() {
        var def = AgentPoolDefinition.builder()
                .agent("worker")
                .pool()
                    .minActive(1)
                    .maxActive(8)
                .build();

        AgentSessionManagerConfig config = def.toSessionManagerConfig();
        assertThat(config.minActive()).isEqualTo(1);
        assertThat(config.maxActive()).isEqualTo(8);
    }

    @Test
    void agentSectionTransitionsToPool() {
        var def = AgentPoolDefinition.builder()
                .agent("reviewer")
                    .workingDir("/reviews")
                    .command("claude")
                .pool()
                    .minActive(1)
                .build();

        assertThat(def.agent().workingDir()).isEqualTo("/reviews");
        assertThat(def.pool().minActive()).isEqualTo(1);
    }

    @Test
    void poolSectionTransitionsBackToAgent() {
        var def = AgentPoolDefinition.builder()
                .agent("reviewer")
                .pool()
                    .minActive(1)
                .agent("updated-name")
                    .workingDir("/updated")
                .build();

        assertThat(def.agent().name()).isEqualTo("updated-name");
        assertThat(def.agent().workingDir()).isEqualTo("/updated");
        assertThat(def.pool().minActive()).isEqualTo(1);
    }

    @Test
    void buildFromPoolSection() {
        var def = AgentPoolDefinition.builder()
                .agent("test")
                .pool()
                    .minActive(3)
                    .maxActive(7)
                .build();

        assertThat(def.pool().minActive()).isEqualTo(3);
        assertThat(def.pool().maxActive()).isEqualTo(7);
    }

    @Test
    void multipleAgentCallsLastWins() {
        var def = AgentPoolDefinition.builder()
                .agent("first")
                    .workingDir("/first")
                .agent("second")
                    .workingDir("/second")
                .build();

        assertThat(def.agent().name()).isEqualTo("second");
        assertThat(def.agent().workingDir()).isEqualTo("/second");
    }
}
