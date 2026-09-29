package io.casehub.claudony.casehub.fleet;

public class NoOpScalingPolicy implements ScalingPolicy {

    @Override
    public ScalingDecision evaluate(PoolSnapshot snapshot) {
        return ScalingDecision.none();
    }
}
