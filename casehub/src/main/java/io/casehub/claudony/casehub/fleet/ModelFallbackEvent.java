package io.casehub.claudony.casehub.fleet;

public record ModelFallbackEvent(
        String poolName,
        String requestedModel,
        String resolvedModel,
        int fallbackDepth) {}
