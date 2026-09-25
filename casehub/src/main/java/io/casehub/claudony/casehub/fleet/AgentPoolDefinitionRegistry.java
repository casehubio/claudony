package io.casehub.claudony.casehub.fleet;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link AgentPoolDefinition}s keyed by agent name.
 * Populated at startup from annotations ({@code @AgentPool}), YAML config, or programmatic registration.
 * Thread-safe — concurrent reads and writes are supported.
 */
public class AgentPoolDefinitionRegistry {

    private final Map<String, AgentPoolDefinition> definitions = new ConcurrentHashMap<>();

    public void register(AgentPoolDefinition definition) {
        var prev = definitions.putIfAbsent(definition.agent().name(), definition);
        if (prev != null) {
            throw new IllegalStateException(
                    "Duplicate agent pool definition for '" + definition.agent().name() + "'");
        }
    }

    public Optional<AgentPoolDefinition> get(String agentName) {
        return Optional.ofNullable(definitions.get(agentName));
    }

    public Collection<AgentPoolDefinition> all() {
        return definitions.values();
    }

    public Set<String> names() {
        return definitions.keySet();
    }

    public boolean isEmpty() {
        return definitions.isEmpty();
    }

    public int size() {
        return definitions.size();
    }
}
