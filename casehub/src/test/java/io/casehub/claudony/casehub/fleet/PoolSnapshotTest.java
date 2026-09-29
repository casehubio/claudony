package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PoolSnapshotTest {

    @Test
    void fillRatioCalculatedFromActiveAndMax() {
        var snapshot = new PoolSnapshot(7, 2, 0, 10, PoolSnapshot.DemandMetrics.ZERO);
        assertThat(snapshot.fillRatio()).isCloseTo(0.7, within(0.001));
    }

    @Test
    void fillRatioZeroWhenMaxIsZero() {
        var snapshot = new PoolSnapshot(0, 0, 0, 0, PoolSnapshot.DemandMetrics.ZERO);
        assertThat(snapshot.fillRatio()).isCloseTo(0.0, within(0.001));
    }

    @Test
    void fillRatioOneWhenFull() {
        var snapshot = new PoolSnapshot(5, 0, 0, 5, PoolSnapshot.DemandMetrics.ZERO);
        assertThat(snapshot.fillRatio()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void fillRatioZeroWhenEmpty() {
        var snapshot = new PoolSnapshot(0, 3, 0, 10, PoolSnapshot.DemandMetrics.ZERO);
        assertThat(snapshot.fillRatio()).isCloseTo(0.0, within(0.001));
    }

    @Test
    void demandMetricsZeroConstant() {
        var zero = PoolSnapshot.DemandMetrics.ZERO;
        assertThat(zero.evictions()).isZero();
        assertThat(zero.exhaustions()).isZero();
        assertThat(zero.acquires()).isZero();
    }
}
