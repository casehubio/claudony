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
    String cooldown,
    String scaleInCooldown
) {}
