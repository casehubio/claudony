package io.casehub.claudony.casehub.fleet;

import io.casehub.yaml.core.step.StepParameterType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPoolSchemaTest {

    @Test
    void definitionHasExpectedInputs() {
        var def = AgentPoolSchema.DEFINITION;
        assertThat(def.name()).isEqualTo("agent-pool");
        assertThat(def.inputs()).containsKey("working-dir");
        assertThat(def.inputs()).containsKey("policy");
        assertThat(def.inputs()).containsKey("command");
        assertThat(def.inputs()).containsKey("pool.min-active");
        assertThat(def.inputs()).containsKey("pool.max-active");
        assertThat(def.inputs()).containsKey("pool.eviction");
    }

    @Test
    void policyHasAllowedValues() {
        var policy = AgentPoolSchema.DEFINITION.inputs().get("policy");
        assertThat(policy.type()).isEqualTo(StepParameterType.STRING);
        assertThat(policy.required()).isFalse();
        assertThat(policy.allowedValues()).containsExactlyInAnyOrder(
                "EXCLUSIVE", "SHARED_READ", "BRANCH_ISOLATED");
    }

    @Test
    void evictionHasAllowedValues() {
        var eviction = AgentPoolSchema.DEFINITION.inputs().get("pool.eviction");
        assertThat(eviction.type()).isEqualTo(StepParameterType.STRING);
        assertThat(eviction.allowedValues()).containsExactlyInAnyOrder(
                "MEMORY_WEIGHTED", "LRU");
    }

    @Test
    void integerFieldsHaveDefaults() {
        var minActive = AgentPoolSchema.DEFINITION.inputs().get("pool.min-active");
        assertThat(minActive.type()).isEqualTo(StepParameterType.INTEGER);
        assertThat(minActive.defaultValue()).isEqualTo("0");

        var maxActive = AgentPoolSchema.DEFINITION.inputs().get("pool.max-active");
        assertThat(maxActive.type()).isEqualTo(StepParameterType.INTEGER);
        assertThat(maxActive.defaultValue()).isEqualTo("10");
    }

    @Test
    void flattenMergesPoolSubMap() {
        var config = Map.<String, Object>of(
                "working-dir", "/workspace",
                "pool", Map.of("min-active", 2, "max-active", 5));

        var flat = AgentPoolSchema.flatten(config);

        assertThat(flat).containsEntry("working-dir", "/workspace");
        assertThat(flat).containsEntry("pool.min-active", 2);
        assertThat(flat).containsEntry("pool.max-active", 5);
        assertThat(flat).doesNotContainKey("pool");
    }

    @Test
    void flattenWithNoPoolSubMap() {
        var config = Map.<String, Object>of("command", "claude");
        var flat = AgentPoolSchema.flatten(config);
        assertThat(flat).containsEntry("command", "claude");
    }
}
