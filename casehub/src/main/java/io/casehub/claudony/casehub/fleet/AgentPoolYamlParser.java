package io.casehub.claudony.casehub.fleet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.yaml.core.step.StepValidator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AgentPoolYamlParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public List<AgentPoolDefinition> parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {return Collections.emptyList();}

        Map<String, Object> root;
        try {
            root = YAML_MAPPER.readValue(yaml, new TypeReference<>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse agent pool YAML", e);
        }

        if (root == null || !root.containsKey("agent-pools")) {return Collections.emptyList();}

        @SuppressWarnings("unchecked")
        var pools = (Map<String, Map<String, Object>>) root.get("agent-pools");
        if (pools == null) {return Collections.emptyList();}

        var results = new ArrayList<AgentPoolDefinition>();
        for (var entry : pools.entrySet()) {
            results.add(toDefinition(entry.getKey(), entry.getValue()));
        }
        return results;
    }

    public void parseInto(String yaml, AgentPoolDefinitionRegistry registry) {
        for (var definition : parse(yaml)) {
            registry.register(definition);
        }
    }

    private AgentPoolDefinition toDefinition(String name, Map<String, Object> config) {
        if (config == null) {config = Map.of();}

        var flat = AgentPoolSchema.flatten(config);
        normalizeEnumValues(flat);
        var errors = StepValidator.validateStep(name, flat, AgentPoolSchema.DEFINITION);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid agent pool definition '" + name + "': " + String.join("; ", errors));
        }

        var agentBuilder = AgentPoolDefinition.builder().agent(name);

        var workingDir = (String) flat.get("working-dir");
        if (workingDir != null) {agentBuilder.workingDir(workingDir);}

        var policy = (String) flat.get("policy");
        if (policy != null) {
            agentBuilder.policy(WorkingDirPolicy.valueOf(policy));
        }

        var command = (String) flat.get("command");
        if (command != null) {agentBuilder.command(command);}

        var poolBuilder = agentBuilder.pool();

        var minActive = flat.get("pool.min-active");
        if (minActive instanceof Number n) {poolBuilder.minActive(n.intValue());}

        var maxActive = flat.get("pool.max-active");
        if (maxActive instanceof Number n) {poolBuilder.maxActive(n.intValue());}

        var eviction = (String) flat.get("pool.eviction");
        if (eviction != null) {
            poolBuilder.eviction(EvictionStrategy.valueOf(eviction));
        }

        @SuppressWarnings("unchecked")
        var scalingMap = (Map<String, Object>) (config.containsKey("pool") && config.get("pool") instanceof Map<?, ?> poolMap
            ? ((Map<String, Object>) poolMap).get("scaling") : null);
        poolBuilder.scaling(parseScaling(scalingMap));

        return poolBuilder.build();
    }

    private static void normalizeEnumValues(Map<String, Object> flat) {
        for (var key : List.of("policy", "pool.eviction")) {
            var value = flat.get(key);
            if (value instanceof String s) {
                flat.put(key, s.toUpperCase().replace('-', '_'));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private ScalingConfig parseScaling(Map<String, Object> scalingMap) {
        if (scalingMap == null || scalingMap.isEmpty()) {
            return ScalingConfig.NoScalingConfig.INSTANCE;
        }

        var type = (String) scalingMap.get("type");
        if (type == null) {
            throw new IllegalArgumentException("scaling config requires a 'type' field");
        }

        Duration cooldown = parseDuration((String) scalingMap.get("cooldown"));
        Duration scaleInCooldown = parseDuration((String) scalingMap.get("scale-in-cooldown"));

        return switch (type) {
            case "target-tracking" -> {
                var target = scalingMap.get("target");
                if (target == null) {
                    throw new IllegalArgumentException("target-tracking scaling requires a 'target' field");
                }
                double targetValue = target instanceof Number n ? n.doubleValue() : Double.parseDouble(target.toString());
                yield new ScalingConfig.TargetTrackingConfig(targetValue, cooldown, scaleInCooldown);
            }
            case "step" -> {
                var stepsList = (List<Map<String, Object>>) scalingMap.get("steps");
                if (stepsList == null || stepsList.isEmpty()) {
                    throw new IllegalArgumentException("step scaling requires a non-empty 'steps' list");
                }
                var steps = stepsList.stream().map(s -> {
                    var threshold = s.get("threshold");
                    var adjustment = s.get("adjustment");
                    return new ScalingStep(
                        threshold instanceof Number n ? n.doubleValue() : Double.parseDouble(threshold.toString()),
                        adjustment instanceof Number n ? n.intValue() : Integer.parseInt(adjustment.toString())
                    );
                }).toList();
                yield new ScalingConfig.StepConfig(steps, cooldown, scaleInCooldown);
            }
            case "none" -> ScalingConfig.NoScalingConfig.INSTANCE;
            default -> new ScalingConfig.CustomScalingConfig(type, cooldown, scaleInCooldown);
        };
    }

    private static Duration parseDuration(String value) {
        if (value == null) return null;
        value = value.trim();
        if (value.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        if (value.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        return Duration.ofSeconds(Long.parseLong(value));
    }

}
