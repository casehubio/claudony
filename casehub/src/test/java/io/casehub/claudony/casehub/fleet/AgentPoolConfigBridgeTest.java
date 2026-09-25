package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPoolConfigBridgeTest {

    @Test
    void fromProviderConfigMap_fullConfig() {
        Map<String, Object> config = Map.of(
                "command", "claude --model opus",
                "workingDir", "/workspace/reviews",
                "policy", "SHARED_READ",
                "pool.minActive", 2,
                "pool.maxActive", 10,
                "pool.eviction", "memory-weighted"
        );

        var def = AgentPoolConfigBridge.fromProviderConfig("code-reviewer", config);

        assertThat(def.agent().name()).isEqualTo("code-reviewer");
        assertThat(def.agent().command()).isEqualTo("claude --model opus");
        assertThat(def.agent().workingDir()).isEqualTo("/workspace/reviews");
        assertThat(def.agent().policy()).isEqualTo(WorkingDirPolicy.SHARED_READ);
        assertThat(def.pool().minActive()).isEqualTo(2);
        assertThat(def.pool().maxActive()).isEqualTo(10);
        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.MEMORY_WEIGHTED);
    }

    @Test
    void fromProviderConfigMap_minimalConfig() {
        var def = AgentPoolConfigBridge.fromProviderConfig("worker", Map.of());

        assertThat(def.agent().name()).isEqualTo("worker");
        assertThat(def.agent().command()).isNull();
        assertThat(def.agent().workingDir()).isNull();
        assertThat(def.agent().policy()).isEqualTo(WorkingDirPolicy.EXCLUSIVE);
        assertThat(def.pool().minActive()).isZero();
        assertThat(def.pool().maxActive()).isEqualTo(10);
    }

    @Test
    void fromProviderConfigMap_poolOnlyFields() {
        Map<String, Object> config = Map.of(
                "pool.maxActive", 5
        );

        var def = AgentPoolConfigBridge.fromProviderConfig("agent", config);
        assertThat(def.pool().maxActive()).isEqualTo(5);
        assertThat(def.pool().minActive()).isZero();
    }

    @Test
    void toProviderConfigMap_fullRoundTrip() {
        var def = AgentPoolDefinition.builder()
                .agent("reviewer")
                    .workingDir("/reviews")
                    .policy(WorkingDirPolicy.SHARED_READ)
                    .command("claude --model opus")
                .pool()
                    .minActive(2)
                    .maxActive(10)
                    .eviction(EvictionStrategy.LRU)
                .build();

        Map<String, Object> map = AgentPoolConfigBridge.toProviderConfig(def);

        assertThat(map).containsEntry("command", "claude --model opus");
        assertThat(map).containsEntry("workingDir", "/reviews");
        assertThat(map).containsEntry("policy", "SHARED_READ");
        assertThat(map).containsEntry("pool.minActive", 2);
        assertThat(map).containsEntry("pool.maxActive", 10);
        assertThat(map).containsEntry("pool.eviction", "LRU");
    }

    @Test
    void roundTrip_fromMapToDefToMap() {
        Map<String, Object> original = Map.of(
                "command", "claude",
                "workingDir", "/work",
                "policy", "EXCLUSIVE",
                "pool.minActive", 1,
                "pool.maxActive", 8,
                "pool.eviction", "MEMORY_WEIGHTED"
        );

        var def = AgentPoolConfigBridge.fromProviderConfig("worker", original);
        Map<String, Object> roundTripped = AgentPoolConfigBridge.toProviderConfig(def);

        assertThat(roundTripped).containsEntry("command", "claude");
        assertThat(roundTripped).containsEntry("workingDir", "/work");
        assertThat(roundTripped).containsEntry("pool.minActive", 1);
        assertThat(roundTripped).containsEntry("pool.maxActive", 8);
    }

    @Test
    void toProviderConfigMap_omitsNullAgentFields() {
        var def = AgentPoolDefinition.builder()
                .agent("minimal")
                .build();

        Map<String, Object> map = AgentPoolConfigBridge.toProviderConfig(def);

        assertThat(map).doesNotContainKey("command");
        assertThat(map).doesNotContainKey("workingDir");
        assertThat(map).containsEntry("policy", "EXCLUSIVE");
        assertThat(map).containsEntry("pool.minActive", 0);
        assertThat(map).containsEntry("pool.maxActive", 10);
    }

    @Test
    void fromProviderConfigMap_stringNumbersConvert() {
        Map<String, Object> config = Map.of(
                "pool.minActive", "3",
                "pool.maxActive", "7"
        );

        var def = AgentPoolConfigBridge.fromProviderConfig("agent", config);
        assertThat(def.pool().minActive()).isEqualTo(3);
        assertThat(def.pool().maxActive()).isEqualTo(7);
    }
}
