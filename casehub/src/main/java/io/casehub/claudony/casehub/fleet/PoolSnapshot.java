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

    public record DemandMetrics(int evictions, int exhaustions, int acquires,
                                long averageAcquireNanos, long maxAcquireNanos,
                                java.util.Map<String, Double> externalMetrics) {
        public static final DemandMetrics ZERO = new DemandMetrics(0, 0, 0, 0L, 0L, java.util.Map.of());

        public long averageAcquireMs() {
            return averageAcquireNanos / 1_000_000;
        }

        public long maxAcquireMs() {
            return maxAcquireNanos / 1_000_000;
        }
    }
}
