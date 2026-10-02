package io.casehub.claudony.server.fleet;

import java.util.List;

public record PoolUpdateRequest(
        Integer minActive,
        Integer maxActive,
        String scalingType,
        Double targetFillRatio,
        List<ScalingStepInput> steps,
        Integer exhaustionThreshold,
        Long latencyThresholdMs,
        Integer targetActive,
        String cooldown,
        String scaleInCooldown,
        Double costLimit,
        Long tokenLimit,
        String window,
        String enforcement,
        String reportInterval,
        String noReportTimeout
) {}
