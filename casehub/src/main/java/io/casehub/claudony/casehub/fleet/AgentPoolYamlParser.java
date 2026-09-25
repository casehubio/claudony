package io.casehub.claudony.casehub.fleet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parses YAML agent pool definitions into {@link AgentPoolDefinition} objects via the fluent builder DSL.
 * Third frontend over the same model: DSL, annotations, YAML.
 *
 * <pre>{@code
 * agent-pools:
 *   code-reviewer:
 *     working-dir: /workspace/reviews
 *     policy: SHARED_READ
 *     command: claude --model opus
 *     pool:
 *       min-active: 2
 *       max-active: 10
 *       eviction: memory-weighted
 * }</pre>
 */
public class AgentPoolYamlParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public List<AgentPoolDefinition> parse(String yaml) {
        if (yaml == null || yaml.isBlank()) return Collections.emptyList();

        Map<String, Object> root;
        try {
            root = YAML_MAPPER.readValue(yaml, new TypeReference<>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse agent pool YAML", e);
        }

        if (root == null || !root.containsKey("agent-pools")) return Collections.emptyList();

        @SuppressWarnings("unchecked")
        var pools = (Map<String, Map<String, Object>>) root.get("agent-pools");
        if (pools == null) return Collections.emptyList();

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
        if (config == null) config = Map.of();

        var agentBuilder = AgentPoolDefinition.builder().agent(name);

        var workingDir = stringValue(config, "working-dir");
        if (workingDir != null) agentBuilder.workingDir(workingDir);

        var policy = stringValue(config, "policy");
        if (policy != null) agentBuilder.policy(parsePolicy(policy));

        var command = stringValue(config, "command");
        if (command != null) agentBuilder.command(command);

        @SuppressWarnings("unchecked")
        var poolConfig = (Map<String, Object>) config.get("pool");
        if (poolConfig != null) {
            var poolBuilder = agentBuilder.pool();

            var minActive = intValue(poolConfig, "min-active");
            if (minActive != null) poolBuilder.minActive(minActive);

            var maxActive = intValue(poolConfig, "max-active");
            if (maxActive != null) poolBuilder.maxActive(maxActive);

            var eviction = stringValue(poolConfig, "eviction");
            if (eviction != null) poolBuilder.eviction(parseEviction(eviction));

            return poolBuilder.build();
        }

        return agentBuilder.build();
    }

    private static String stringValue(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private static Integer intValue(Map<String, Object> map, String key) {
        var value = map.get(key);
        if (value instanceof Number n) return n.intValue();
        return null;
    }

    private static WorkingDirPolicy parsePolicy(String value) {
        return WorkingDirPolicy.valueOf(value.toUpperCase().replace('-', '_'));
    }

    private static EvictionStrategy parseEviction(String value) {
        return EvictionStrategy.valueOf(value.toUpperCase().replace('-', '_'));
    }
}
