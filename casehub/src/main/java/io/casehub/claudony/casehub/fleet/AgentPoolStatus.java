package io.casehub.claudony.casehub.fleet;

public record AgentPoolStatus(
        int min,
        int max,
        int active,
        int idle,
        int total,
        AgentPoolHealth health
) {}
