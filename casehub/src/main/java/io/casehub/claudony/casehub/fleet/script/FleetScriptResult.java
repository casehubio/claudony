package io.casehub.claudony.casehub.fleet.script;

import java.util.List;
import java.util.Objects;

public record FleetScriptResult(List<NodeResult> results) {
    public FleetScriptResult {
        Objects.requireNonNull(results);
        results = List.copyOf(results);
    }

    public boolean allSucceeded() {
        return results.stream().allMatch(NodeResult::success);
    }

    public List<NodeResult> failures() {
        return results.stream().filter(r -> !r.success()).toList();
    }
}
