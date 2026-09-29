package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NoOpScalingPolicyTest {

    private final ScalingPolicy policy = new NoOpScalingPolicy();

    @Test
    void alwaysReturnsNone() {
        var snapshot = new PoolSnapshot(5, 2, 0, 10, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.NONE);
        assertThat(decision.count()).isZero();
    }

    @Test
    void returnsNoneEvenWhenFull() {
        var snapshot = new PoolSnapshot(10, 0, 0, 10, PoolSnapshot.DemandMetrics.ZERO);
        var decision = policy.evaluate(snapshot);
        assertThat(decision.direction()).isEqualTo(ScalingDirection.NONE);
    }
}
