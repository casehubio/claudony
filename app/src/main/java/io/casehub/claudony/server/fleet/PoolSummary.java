package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.AgentPoolStatus;

public record PoolSummary(String name, AgentPoolStatus status, String scalingType) {}
