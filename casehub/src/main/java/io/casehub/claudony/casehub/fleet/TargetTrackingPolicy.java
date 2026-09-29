package io.casehub.claudony.casehub.fleet;

public class TargetTrackingPolicy implements ScalingPolicy {

    private final double targetFillRatio;

    public TargetTrackingPolicy(double targetFillRatio) {
        if (targetFillRatio <= 0.0 || targetFillRatio > 1.0) {
            throw new IllegalArgumentException("targetFillRatio must be in (0.0, 1.0]");
        }
        this.targetFillRatio = targetFillRatio;
    }

    @Override
    public ScalingDecision evaluate(PoolSnapshot snapshot) {
        double fillRatio = snapshot.fillRatio();

        if (fillRatio > targetFillRatio) {
            int desiredMax = (int) Math.ceil(snapshot.activeCount() / targetFillRatio);
            int increase = desiredMax - snapshot.maxActive();
            if (increase > 0) {
                return ScalingDecision.scaleOut(increase,
                    "fillRatio %.0f%% exceeds target %.0f%%"
                        .formatted(fillRatio * 100, targetFillRatio * 100));
            }
        }

        double scaleInThreshold = targetFillRatio * 0.7;
        if (fillRatio < scaleInThreshold && snapshot.maxActive() > snapshot.minActive()) {
            int desiredMax = Math.max(
                (int) Math.ceil(snapshot.activeCount() / targetFillRatio),
                snapshot.minActive());
            int decrease = snapshot.maxActive() - desiredMax;
            if (decrease > 0) {
                return ScalingDecision.scaleIn(decrease,
                    "fillRatio %.0f%% below threshold %.0f%%"
                        .formatted(fillRatio * 100, scaleInThreshold * 100));
            }
        }

        return ScalingDecision.none();
    }
}
