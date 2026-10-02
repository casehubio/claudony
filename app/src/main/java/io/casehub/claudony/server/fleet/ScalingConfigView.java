package io.casehub.claudony.server.fleet;

import java.util.List;

public record ScalingConfigView(
        Double targetFillRatio,
        List<ScalingStepView> steps,
        Integer exhaustionThreshold,
        Long latencyThresholdMs,
        String beanName,
        Integer targetActive,
        String cooldown,
        String scaleInCooldown
) {
    public record ScalingStepView(double threshold, int adjustment) {}
}
