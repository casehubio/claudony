package io.casehub.claudony.casehub.fleet;

/** Snapshot of pool state: capacity, active/idle counts, and health. Returned by {@code GET /api/agent-pools}. */
public record AgentPoolStatus(
        int min,
        int max,
        int active,
        int idle,
        int total,
        AgentPoolHealth health
) {}
