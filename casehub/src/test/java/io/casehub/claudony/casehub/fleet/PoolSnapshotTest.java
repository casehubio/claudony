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

    @Test
    void demandMetricsZeroIncludesLatencyAndExternalFields() {
        var zero = PoolSnapshot.DemandMetrics.ZERO;
        assertThat(zero.averageAcquireNanos()).isZero();
        assertThat(zero.maxAcquireNanos()).isZero();
        assertThat(zero.externalMetrics()).isEmpty();
    }

    @Test
    void demandMetricsAcquireMsConvertsFromNanos() {
        var metrics = new PoolSnapshot.DemandMetrics(0, 0, 0,
                                                     5_000_000L, 12_000_000L, java.util.Map.of());
        assertThat(metrics.averageAcquireMs()).isEqualTo(5L);
        assertThat(metrics.maxAcquireMs()).isEqualTo(12L);
    }

    @Test
    void demandMetricsExternalMetricsImmutable() {
        var mutable = new java.util.HashMap<String, Double>();
        mutable.put("http.queue_depth", 3.0);
        var metrics = new PoolSnapshot.DemandMetrics(0, 0, 0, 0L, 0L,
                                                     java.util.Map.copyOf(mutable));
        mutable.put("injected", 1.0);
        assertThat(metrics.externalMetrics()).doesNotContainKey("injected");
    }
}
