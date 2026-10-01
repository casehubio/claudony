package io.casehub.claudony.casehub.fleet;

public class DemandPressurePolicy implements ScalingPolicy {

    private final int exhaustionThreshold;
    private final long latencyThresholdNanos;

    public DemandPressurePolicy(int exhaustionThreshold, long latencyThresholdMs) {
        if (exhaustionThreshold < 0)
            throw new IllegalArgumentException("exhaustionThreshold must be non-negative");
        if (latencyThresholdMs <= 0)
            throw new IllegalArgumentException("latencyThresholdMs must be positive");
        this.exhaustionThreshold = exhaustionThreshold;
        this.latencyThresholdNanos = latencyThresholdMs * 1_000_000;
    }

    @Override
    public ScalingDecision evaluate(PoolSnapshot snapshot) {
        var demand = snapshot.demand();
        boolean exhaustionTriggered = demand.exhaustions() > exhaustionThreshold;
        boolean latencyTriggered = demand.averageAcquireNanos() > latencyThresholdNanos;

        if (exhaustionTriggered) {
            return ScalingDecision.scaleOut(1,
                "exhaustions %d > threshold %d"
                    .formatted(demand.exhaustions(), exhaustionThreshold));
        }
        if (latencyTriggered) {
            return ScalingDecision.scaleOut(1,
                "avgAcquireLatency %dms > threshold %dms"
                    .formatted(demand.averageAcquireMs(), latencyThresholdNanos / 1_000_000));
        }

        boolean exhaustionQuiet = demand.exhaustions() <= exhaustionThreshold / 2;
        boolean latencyQuiet = demand.averageAcquireNanos() <= latencyThresholdNanos / 2;

        if (exhaustionQuiet && latencyQuiet && snapshot.maxActive() > snapshot.minActive()) {
            return ScalingDecision.scaleIn(1, "demand pressure below hysteresis band");
        }

        return ScalingDecision.none();
    }
}
