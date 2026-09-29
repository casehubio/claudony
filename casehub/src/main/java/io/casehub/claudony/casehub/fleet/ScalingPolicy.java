package io.casehub.claudony.casehub.fleet;

public interface ScalingPolicy {
    ScalingDecision evaluate(PoolSnapshot snapshot);
}
