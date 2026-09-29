package io.casehub.claudony.casehub.fleet;

public record ScalingDecision(ScalingDirection direction, int count, String reason) {

    public static ScalingDecision none() {
        return new ScalingDecision(ScalingDirection.NONE, 0, "no action needed");
    }

    public static ScalingDecision scaleOut(int count, String reason) {
        return new ScalingDecision(ScalingDirection.OUT, count, reason);
    }

    public static ScalingDecision scaleIn(int count, String reason) {
        return new ScalingDecision(ScalingDirection.IN, count, reason);
    }
}
