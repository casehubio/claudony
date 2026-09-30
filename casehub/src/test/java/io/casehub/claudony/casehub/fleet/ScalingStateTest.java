package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class ScalingStateTest {

    @Test
    void cooldownRemaining_scaleOut_returnsDuration() {
        var config = new ScalingConfig.TargetTrackingConfig(0.7, Duration.ofSeconds(60), Duration.ofSeconds(300));
        var now = Instant.now();
        var state = new ScalingState(
            ScalingDecision.scaleOut(2, "test"), now.minusSeconds(20),
            now.minusSeconds(20), null, config);
        assertThat(state.cooldownRemaining(now)).isBetween(Duration.ofSeconds(39), Duration.ofSeconds(41));
    }

    @Test
    void cooldownRemaining_expired_returnsZero() {
        var config = new ScalingConfig.TargetTrackingConfig(0.7, Duration.ofSeconds(60), Duration.ofSeconds(300));
        var now = Instant.now();
        var state = new ScalingState(
            ScalingDecision.scaleOut(2, "test"), now.minusSeconds(120),
            now.minusSeconds(120), null, config);
        assertThat(state.cooldownRemaining(now)).isEqualTo(Duration.ZERO);
    }

    @Test
    void cooldownRemaining_noScaling_returnsZero() {
        var state = new ScalingState(null, null, null, null, ScalingConfig.NoScalingConfig.INSTANCE);
        assertThat(state.cooldownRemaining(Instant.now())).isEqualTo(Duration.ZERO);
    }

    @Test
    void cooldownRemaining_scaleIn_usesScaleInCooldown() {
        var config = new ScalingConfig.TargetTrackingConfig(0.7, Duration.ofSeconds(60), Duration.ofSeconds(300));
        var now = Instant.now();
        var state = new ScalingState(
            ScalingDecision.scaleIn(1, "test"), now.minusSeconds(100),
            null, now.minusSeconds(100), config);
        assertThat(state.cooldownRemaining(now)).isBetween(Duration.ofSeconds(199), Duration.ofSeconds(201));
    }

    @Test
    void cooldownRemaining_bothTimestamps_returnsLarger() {
        var config = new ScalingConfig.TargetTrackingConfig(0.7, Duration.ofSeconds(60), Duration.ofSeconds(300));
        var now = Instant.now();
        var state = new ScalingState(
            ScalingDecision.scaleOut(2, "test"), now.minusSeconds(10),
            now.minusSeconds(10), now.minusSeconds(50), config);
        var remaining = state.cooldownRemaining(now);
        assertThat(remaining).isBetween(Duration.ofSeconds(249), Duration.ofSeconds(251));
    }
}
