package io.casehub.claudony.casehub.fleet;

/** Health state of the agent pool. DEGRADED when nearing capacity; UNHEALTHY when exhausted. */
public enum AgentPoolHealth {
    HEALTHY, DEGRADED, UNHEALTHY
}
