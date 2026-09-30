package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPoolDefinitionRegistryUpdateTest {

    @Test
    void updateScaling_replacesConfig() {
        var registry = new AgentPoolDefinitionRegistry();
        var def = AgentPoolDefinition.builder().agent("test").pool()
            .scaling(new ScalingConfig.TargetTrackingConfig(0.7, null, null)).build();
        registry.register(def);

        var newConfig = new ScalingConfig.StepConfig(
            List.of(new ScalingStep(0.8, 1)), null, null);
        registry.updateScaling("test", newConfig);

        assertThat(registry.get("test").get().pool().scaling()).isInstanceOf(ScalingConfig.StepConfig.class);
    }

    @Test
    void updateScaling_unknownPool_throws() {
        var registry = new AgentPoolDefinitionRegistry();
        assertThatThrownBy(() -> registry.updateScaling("missing", ScalingConfig.NoScalingConfig.INSTANCE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateCapacity_updatesMinMax() {
        var registry = new AgentPoolDefinitionRegistry();
        var def = AgentPoolDefinition.builder().agent("test").pool().minActive(0).maxActive(10).build();
        registry.register(def);

        registry.updateCapacity("test", 2, 20);

        var updated = registry.get("test").get();
        assertThat(updated.pool().minActive()).isEqualTo(2);
        assertThat(updated.pool().maxActive()).isEqualTo(20);
    }

    @Test
    void updateCapacity_preservesScalingAndEviction() {
        var registry = new AgentPoolDefinitionRegistry();
        var scaling = new ScalingConfig.TargetTrackingConfig(0.7, null, null);
        var def = AgentPoolDefinition.builder().agent("test").pool()
            .minActive(0).maxActive(10).scaling(scaling).build();
        registry.register(def);

        registry.updateCapacity("test", 1, 15);

        var updated = registry.get("test").get();
        assertThat(updated.pool().scaling()).isSameAs(scaling);
        assertThat(updated.pool().eviction()).isEqualTo(def.pool().eviction());
    }
}
