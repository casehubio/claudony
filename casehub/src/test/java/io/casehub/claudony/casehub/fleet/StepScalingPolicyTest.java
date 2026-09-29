package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepScalingPolicyTest {

    @Test
    void scalesOutAtHighestMatchingThreshold() {
        var policy = new StepScalingPolicy(List.of(
            new ScalingStep(0.9, 4),
            new ScalingStep(0.8, 2)
        ));
        var snapshot = new PoolSnapshot(19, 0, 0, 20, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.OUT);
        assertThat(decision.count()).isEqualTo(4);
    }

    @Test
    void scalesOutAtLowerThresholdWhenHigherNotMet() {
        var policy = new StepScalingPolicy(List.of(
            new ScalingStep(0.9, 4),
            new ScalingStep(0.8, 2)
        ));
        var snapshot = new PoolSnapshot(17, 0, 0, 20, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.OUT);
        assertThat(decision.count()).isEqualTo(2);
    }

    @Test
    void scalesInAtNegativeAdjustmentThreshold() {
        var policy = new StepScalingPolicy(List.of(
            new ScalingStep(0.8, 2),
            new ScalingStep(0.3, -1)
        ));
        var snapshot = new PoolSnapshot(2, 0, 0, 10, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.IN);
        assertThat(decision.count()).isEqualTo(1);
    }

    @Test
    void noActionWhenNoThresholdMatched() {
        var policy = new StepScalingPolicy(List.of(
            new ScalingStep(0.8, 2),
            new ScalingStep(0.3, -1)
        ));
        var snapshot = new PoolSnapshot(5, 0, 0, 10, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.NONE);
    }

    @Test
    void graduatedScaleInMatchesMostAggressiveStep() {
        var policy = new StepScalingPolicy(List.of(
            new ScalingStep(0.8, 2),
            new ScalingStep(0.3, -1),
            new ScalingStep(0.1, -5)
        ));
        // fillRatio = 0.05 → below both 0.3 and 0.1, should match -5 (most aggressive)
        var snapshot = new PoolSnapshot(1, 0, 0, 20, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.IN);
        assertThat(decision.count()).isEqualTo(5);
    }

    @Test
    void emptyStepsListThrows() {
        assertThatThrownBy(() -> new StepScalingPolicy(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overlappingThresholdsThrow() {
        assertThatThrownBy(() -> new StepScalingPolicy(List.of(
            new ScalingStep(0.5, 2),
            new ScalingStep(0.5, -1)
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scaleInThresholdAboveScaleOutThrows() {
        assertThatThrownBy(() -> new StepScalingPolicy(List.of(
            new ScalingStep(0.4, 2),
            new ScalingStep(0.6, -1)
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroAdjustmentThrows() {
        assertThatThrownBy(() -> new StepScalingPolicy(List.of(
            new ScalingStep(0.8, 0)
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thresholdOutOfRangeThrows() {
        assertThatThrownBy(() -> new StepScalingPolicy(List.of(
            new ScalingStep(0.0, 2)
        ))).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new StepScalingPolicy(List.of(
            new ScalingStep(1.0, 2)
        ))).isInstanceOf(IllegalArgumentException.class);
    }
}
