package io.casehub.claudony.casehub.fleet;

import java.util.List;
import java.util.Objects;

public record ApplicationSpec(
        String name,
        String displayName,
        String description,
        List<PoolSpec> pools
) {
    public ApplicationSpec {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        Objects.requireNonNull(displayName, "displayName");
        if (pools == null) pools = List.of();
        pools = List.copyOf(pools);
    }

    public record PoolSpec(
            String agentId,
            int minActive,
            int maxActive
    ) {
        public PoolSpec {
            Objects.requireNonNull(agentId, "agentId");
            if (agentId.isBlank()) throw new IllegalArgumentException("agentId must not be blank");
            if (minActive < 0) throw new IllegalArgumentException("minActive must be >= 0");
            if (maxActive < 1) throw new IllegalArgumentException("maxActive must be >= 1");
            if (maxActive < minActive) throw new IllegalArgumentException("maxActive must be >= minActive");
        }
    }
}
