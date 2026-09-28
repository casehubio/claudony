package io.casehub.claudony.casehub.fleet;

import io.casehub.yaml.core.step.StepDefinition;
import io.casehub.yaml.core.step.StepParameter;
import io.casehub.yaml.core.step.StepParameterType;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentPoolSchema {

    private AgentPoolSchema() {}

    public static final StepDefinition DEFINITION;

    static {
        var inputs = new LinkedHashMap<String, StepParameter>();

        inputs.put("working-dir", new StepParameter(
                StepParameterType.STRING, false, null, null, null, "Default working directory"));

        inputs.put("policy", new StepParameter(
                StepParameterType.STRING, false, "EXCLUSIVE",
                Arrays.stream(WorkingDirPolicy.values()).map(Enum::name).toList(),
                null, "Working directory concurrency policy"));

        inputs.put("command", new StepParameter(
                StepParameterType.STRING, false, null, null, null, "CLI command to run"));

        inputs.put("pool.min-active", new StepParameter(
                StepParameterType.INTEGER, false, "0", null, null, "Minimum pre-warmed sessions"));

        inputs.put("pool.max-active", new StepParameter(
                StepParameterType.INTEGER, false, "10", null, null, "Maximum concurrent active sessions"));

        inputs.put("pool.eviction", new StepParameter(
                StepParameterType.STRING, false, "MEMORY_WEIGHTED",
                Arrays.stream(EvictionStrategy.values()).map(Enum::name).toList(),
                null, "Eviction strategy when pool is at capacity"));

        DEFINITION = new StepDefinition("agent-pool", "Agent pool definition", inputs, Map.of(), null);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> flatten(Map<String, Object> config) {
        var flat = new LinkedHashMap<String, Object>();
        for (var entry : config.entrySet()) {
            if ("pool".equals(entry.getKey()) && entry.getValue() instanceof Map<?, ?> poolMap) {
                for (var poolEntry : ((Map<String, Object>) poolMap).entrySet()) {
                    flat.put("pool." + poolEntry.getKey(), poolEntry.getValue());
                }
            } else {
                flat.put(entry.getKey(), entry.getValue());
            }
        }
        return flat;
    }
}
