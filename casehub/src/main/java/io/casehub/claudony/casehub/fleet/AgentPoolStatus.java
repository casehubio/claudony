package io.casehub.claudony.casehub.fleet;

/** Snapshot of pool state: capacity, active/idle counts, and health. */
public record AgentPoolStatus(
        int min,
        int max,
        int active,
        int idle,
        int total,
        AgentPoolHealth health
) {}
