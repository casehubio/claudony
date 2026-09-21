package io.casehub.claudony.casehub.fleet;

import java.time.Duration;

public record AgentPoolConfig(
        int min,
        int max,
        Duration idleTimeout,
        Duration acquireTimeout
) {
    public AgentPoolConfig {
        if (min < 0) throw new IllegalArgumentException("min must be >= 0");
        if (max < 1) throw new IllegalArgumentException("max must be >= 1");
        if (max < min) throw new IllegalArgumentException("max must be >= min");
    }
}
