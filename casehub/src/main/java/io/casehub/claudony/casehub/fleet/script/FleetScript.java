package io.casehub.claudony.casehub.fleet.script;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FleetScript(
    DesiredStateHeader desiredState,
    Map<String, String> variables,
    Map<String, FleetNode> nodes
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DesiredStateHeader(String namespace, String name) {}

    public FleetScript {
        if (nodes == null || nodes.isEmpty())
            throw new IllegalArgumentException("nodes must not be empty");
    }
}
