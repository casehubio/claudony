package io.casehub.claudony.casehub.fleet;

import java.time.Duration;

public record BudgetCheckResult(
        boolean overBudget,
        BudgetDimension violatedDimension,
        double currentCostUsd,
        double costLimit,
        long currentTokens,
        long tokenLimit,
        Duration windowRemaining
) {}
