package io.casehub.claudony.server.fleet;

public record ScalingConfigUpdate(String type, Double targetFillRatio,
    String cooldown, String scaleInCooldown) {}
