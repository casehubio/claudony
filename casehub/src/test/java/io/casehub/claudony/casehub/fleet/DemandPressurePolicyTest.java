package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemandPressurePolicyTest {

    private final DemandPressurePolicy policy =
        new DemandPressurePolicy(2, 500);

    private PoolSnapshot snap(int active, int max, int min,
            int exhaustions, long avgNanos) {
        return new PoolSnapshot(active, 0, min, max,
            new PoolSnapshot.DemandMetrics(0, exhaustions, 0,
                avgNanos, avgNanos, Map.of()));
    }

    @Test
    void scalesOutWhenExhaustionsExceedThreshold() {
        var decision = policy.evaluate(snap(10, 10, 2, 3, 0));
        assertThat(decision.direction()).isEqualTo(ScalingDirection.OUT);
        assertThat(decision.count()).isEqualTo(1);
    }

    @Test
    void scalesOutWhenLatencyExceedsThreshold() {
        var decision = policy.evaluate(
            snap(5, 10, 2, 0, 600_000_000L));
        assertThat(decision.direction()).isEqualTo(ScalingDirection.OUT);
        assertThat(decision.count()).isEqualTo(1);
    }

    @Test
    void exhaustionTakesPriorityOverLatency() {
        var decision = policy.evaluate(
            snap(10, 10, 2, 5, 600_000_000L));
        assertThat(decision.direction()).isEqualTo(ScalingDirection.OUT);
        assertThat(decision.reason()).contains("exhaustions");
    }

    @Test
    void noActionWhenBelowThresholdsButAboveHysteresis() {
        var decision = policy.evaluate(
            snap(5, 10, 2, 1, 300_000_000L));
        assertThat(decision.direction()).isEqualTo(ScalingDirection.NONE);
    }

    @Test
    void scalesInWhenBothBelowHysteresisBand() {
        var decision = policy.evaluate(
            snap(5, 10, 2, 0, 100_000_000L));
        assertThat(decision.direction()).isEqualTo(ScalingDirection.IN);
        assertThat(decision.count()).isEqualTo(1);
    }

    @Test
    void noScaleInWhenAtMinActive() {
        var decision = policy.evaluate(
            snap(2, 2, 2, 0, 0));
        assertThat(decision.direction()).isEqualTo(ScalingDirection.NONE);
    }

    @Test
    void exactlyAtExhaustionThresholdDoesNotTrigger() {
        var decision = policy.evaluate(snap(5, 10, 2, 2, 0));
        assertThat(decision.direction()).isNotEqualTo(ScalingDirection.OUT);
    }

    @Test
    void rejectsNegativeExhaustionThreshold() {
        assertThatThrownBy(() -> new DemandPressurePolicy(-1, 500))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroLatencyThreshold() {
        assertThatThrownBy(() -> new DemandPressurePolicy(2, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
