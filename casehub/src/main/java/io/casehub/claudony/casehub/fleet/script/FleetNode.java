package io.casehub.claudony.casehub.fleet.script;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record FleetNode(String type, Map<String, Object> spec, List<String> dependsOn) {
    public FleetNode {
        Objects.requireNonNull(type, "node type is required");
        if (type.isBlank()) throw new IllegalArgumentException("node type must not be blank");
        if (spec == null) spec = Map.of();
        if (dependsOn == null) dependsOn = List.of();
    }
}
