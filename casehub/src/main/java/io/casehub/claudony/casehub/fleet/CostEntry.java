package io.casehub.claudony.casehub.fleet;

import java.time.Instant;

public record CostEntry(
        String poolName,
        String sessionId,
        double deltaCostUsd,
        long deltaTokens,
        String model,
        Instant timestamp
) {}
