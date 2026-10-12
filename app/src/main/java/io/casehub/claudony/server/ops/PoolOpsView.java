package io.casehub.claudony.server.ops;

public record PoolOpsView(String name, String scalingType, int active, int idle, int max,
                           double fillRatio, String healthStatus) {}
