package io.casehub.claudony.casehub.fleet;

import java.time.Instant;

public record CostReport(
        String sessionId,
        double totalCostUsd,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheCreationTokens,
        String model,
        Instant timestamp
) {}
