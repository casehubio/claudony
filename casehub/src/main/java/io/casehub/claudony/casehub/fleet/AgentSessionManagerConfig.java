package io.casehub.claudony.casehub.fleet;

public record AgentSessionManagerConfig(
        int minActive,
        int maxActive
) {
    public AgentSessionManagerConfig {
        if (minActive < 0) throw new IllegalArgumentException("minActive must be >= 0");
        if (maxActive < 1) throw new IllegalArgumentException("maxActive must be >= 1");
        if (maxActive < minActive) throw new IllegalArgumentException("maxActive must be >= minActive");
    }
}
