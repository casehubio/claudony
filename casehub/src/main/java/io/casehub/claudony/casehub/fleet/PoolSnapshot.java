package io.casehub.claudony.casehub.fleet;

public record PoolSnapshot(
    int activeCount,
    int suspendedCount,
    int minActive,
    int maxActive,
    DemandMetrics demand
) {
    public double fillRatio() {
        return maxActive > 0 ? activeCount / (double) maxActive : 0.0;
    }

    public record DemandMetrics(int evictions, int exhaustions, int acquires) {
        public static final DemandMetrics ZERO = new DemandMetrics(0, 0, 0);
    }
}
