package io.casehub.claudony.casehub.fleet;

import io.casehub.yaml.core.step.Declaration;
import io.casehub.yaml.plugin.api.Parameter;
import io.casehub.yaml.plugin.api.ParameterType;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentPoolSchema {

    private AgentPoolSchema() {}

    public static final Declaration DEFINITION;

    static {
        var inputs = new LinkedHashMap<String, Parameter>();

        inputs.put("working-dir", new Parameter(
                ParameterType.STRING, false, null, null, null, "Default working directory"));

        inputs.put("policy", new Parameter(
                ParameterType.STRING, false, "EXCLUSIVE",
                Arrays.stream(WorkingDirPolicy.values()).map(Enum::name).toList(),
                null, "Working directory concurrency policy"));

        inputs.put("command", new Parameter(
                ParameterType.STRING, false, null, null, null, "CLI command to run"));

        inputs.put("model-chain", new Parameter(
                ParameterType.ARRAY, false, null, null, null,
                "Ordered model fallback chain (string or structured entries)"));

        inputs.put("pool.min-active", new Parameter(
                ParameterType.INTEGER, false, "0", null, null, "Minimum pre-warmed sessions"));

        inputs.put("pool.max-active", new Parameter(
                ParameterType.INTEGER, false, "10", null, null, "Maximum concurrent active sessions"));

        inputs.put("pool.eviction", new Parameter(
                ParameterType.STRING, false, "MEMORY_WEIGHTED",
                Arrays.stream(EvictionStrategy.values()).map(Enum::name).toList(),
                null, "Eviction strategy when pool is at capacity"));

        inputs.put("pool.scaling.exhaustion-threshold", new Parameter(
                ParameterType.INTEGER, false, null, null, null,
                "Exhaustion count per tick that triggers scale-out (demand-pressure)"));

        inputs.put("pool.scaling.latency-threshold-ms", new Parameter(
                ParameterType.INTEGER, false, null, null, null,
                "Average acquire latency in ms that triggers scale-out (demand-pressure)"));

        DEFINITION = new Declaration("agent-pool", "Agent pool definition", inputs, Map.of(), null);
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
