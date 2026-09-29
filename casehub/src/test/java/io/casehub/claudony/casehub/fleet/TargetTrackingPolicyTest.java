package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TargetTrackingPolicyTest {

    @Test
    void scalesOutWhenFillRatioExceedsTarget() {
        var policy = new TargetTrackingPolicy(0.7);
        var snapshot = new PoolSnapshot(8, 0, 2, 10, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.OUT);
        // desiredMax = ceil(8 / 0.7) = 12, increase = 12 - 10 = 2
        assertThat(decision.count()).isEqualTo(2);
    }

    @Test
    void scalesInWhenFillRatioBelowHysteresisBand() {
        var policy = new TargetTrackingPolicy(0.7);
        var snapshot = new PoolSnapshot(3, 0, 2, 10, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.IN);
        // desiredMax = max(ceil(3 / 0.7), 2) = max(5, 2) = 5, decrease = 10 - 5 = 5
        assertThat(decision.count()).isEqualTo(5);
    }

    @Test
    void noActionWhenInHysteresisBand() {
        var policy = new TargetTrackingPolicy(0.7);
        var snapshot = new PoolSnapshot(6, 0, 2, 10, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.NONE);
    }

    @Test
    void scaleInRespectsMinActive() {
        var policy = new TargetTrackingPolicy(0.7);
        var snapshot = new PoolSnapshot(0, 0, 5, 10, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.IN);
        assertThat(decision.count()).isEqualTo(5);
    }

    @Test
    void noScaleInWhenAlreadyAtMinActive() {
        var policy = new TargetTrackingPolicy(0.7);
        var snapshot = new PoolSnapshot(0, 0, 5, 5, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.NONE);
    }

    @Test
    void noScaleOutWhenExactlyAtTarget() {
        var policy = new TargetTrackingPolicy(0.7);
        var snapshot = new PoolSnapshot(7, 0, 0, 10, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.NONE);
    }

    @Test
    void emptyPoolScalesInToMinActive() {
        var policy = new TargetTrackingPolicy(0.7);
        var snapshot = new PoolSnapshot(0, 0, 2, 10, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.IN);
        assertThat(decision.count()).isEqualTo(8);
    }

    @Test
    void scaleOutStabilizesAfterOneStep() {
        var policy = new TargetTrackingPolicy(0.7);
        // After scaleOut: active=8, max=12 → fill=0.67 → in hysteresis band
        var after = new PoolSnapshot(8, 0, 2, 12, PoolSnapshot.DemandMetrics.ZERO);
        assertThat(policy.evaluate(after).direction()).isEqualTo(ScalingDirection.NONE);
    }
}
