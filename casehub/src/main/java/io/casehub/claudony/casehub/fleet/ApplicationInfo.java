package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.api.registry.HealthStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ApplicationInfo(
        String name,
        String displayName,
        String description,
        HealthStatus health,
        Instant registeredAt,
        List<AppPoolInfo> pools
) {
    public ApplicationInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(registeredAt, "registeredAt");
        if (pools == null) pools = List.of();
        pools = List.copyOf(pools);
    }

    public record AppPoolInfo(
            String agentId,
            int minActive,
            int maxActive
    ) {}
}
