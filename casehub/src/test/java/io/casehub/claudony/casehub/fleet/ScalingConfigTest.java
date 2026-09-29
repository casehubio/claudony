package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScalingConfigTest {

    @Test
    void targetTrackingDefaults() {
        var config = new ScalingConfig.TargetTrackingConfig(0.7, null, null);
        assertThat(config.targetFillRatio()).isEqualTo(0.7);
        assertThat(config.cooldown()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.scaleInCooldown()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void targetTrackingCustomCooldowns() {
        var config = new ScalingConfig.TargetTrackingConfig(
            0.8, Duration.ofSeconds(30), Duration.ofSeconds(300));
        assertThat(config.cooldown()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.scaleInCooldown()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void targetTrackingInvalidFillRatio() {
        assertThatThrownBy(() -> new ScalingConfig.TargetTrackingConfig(0.0, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScalingConfig.TargetTrackingConfig(1.1, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScalingConfig.TargetTrackingConfig(-0.1, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepConfigDefaults() {
        var config = new ScalingConfig.StepConfig(
            List.of(new ScalingStep(0.8, 2)), null, null);
        assertThat(config.cooldown()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.scaleInCooldown()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void stepConfigEmptyStepsThrows() {
        assertThatThrownBy(() -> new ScalingConfig.StepConfig(List.of(), null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepConfigValidatesStepsAtConstructionTime() {
        assertThatThrownBy(() -> new ScalingConfig.StepConfig(
            List.of(new ScalingStep(0.8, 0)), null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("zero");
    }

    @Test
    void customScalingConfigBlankBeanNameThrows() {
        assertThatThrownBy(() -> new ScalingConfig.CustomScalingConfig("", null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noScalingConfigSingleton() {
        assertThat(ScalingConfig.NoScalingConfig.INSTANCE.cooldown()).isEqualTo(Duration.ZERO);
        assertThat(ScalingConfig.NoScalingConfig.INSTANCE.scaleInCooldown()).isEqualTo(Duration.ZERO);
    }

    @Test
    void sealedHierarchyPatternMatching() {
        ScalingConfig config = new ScalingConfig.TargetTrackingConfig(0.7, null, null);
        String type = switch (config) {
            case ScalingConfig.TargetTrackingConfig t -> "target";
            case ScalingConfig.StepConfig s -> "step";
            case ScalingConfig.CustomScalingConfig c -> "custom";
            case ScalingConfig.NoScalingConfig n -> "none";
        };
        assertThat(type).isEqualTo("target");
    }
}
