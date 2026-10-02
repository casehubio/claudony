package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.AgentPoolDefinition;
import io.casehub.claudony.casehub.fleet.AgentPoolStatus;

public record PoolDetail(
        String name,
        AgentPoolStatus status,
        DefinitionView definition,
        ScalingView scaling,
        DemandView demand,
        BudgetView budget
) {
    public record DefinitionView(AgentPoolDefinition.AgentConfig agent, PoolConfigView pool) {}

    public record PoolConfigView(int minActive, int maxActive, String eviction) {}

    public record ScalingView(String type, ScalingConfigView config, DecisionView lastDecision,
                              String cooldownRemaining) {}

    public record DecisionView(String direction, int count, String reason, String timestamp) {}

    public record DemandView(double acquires, double evictions, double exhaustions) {}

    public record BudgetView(
            double currentCostUsd, Double costLimit,
            long currentTokens, Long tokenLimit,
            String windowRemaining, String enforcement,
            String status
    ) {}
}
