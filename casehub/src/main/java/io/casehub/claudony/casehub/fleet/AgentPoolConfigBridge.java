package io.casehub.claudony.casehub.fleet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges between ops {@code ProviderConfig} maps and {@link AgentPoolDefinition}.
 * Ops stores agent config as {@code ProviderConfig("claudony", Map<String, Object>)};
 * this bridge converts that map to/from a typed definition via the fluent builder DSL.
 *
 * <p>Map key conventions:
 * <ul>
 *   <li>{@code command}, {@code workingDir}, {@code policy} — agent config</li>
 *   <li>{@code pool.minActive}, {@code pool.maxActive}, {@code pool.eviction} — pool config</li>
 * </ul>
 */
public final class AgentPoolConfigBridge {

    private AgentPoolConfigBridge() {}

    public static AgentPoolDefinition fromProviderConfig(String agentName, Map<String, Object> config) {
        var agentBuilder = AgentPoolDefinition.builder().agent(agentName);

        var command = stringValue(config, "command");
        if (command != null) agentBuilder.command(command);

        var workingDir = stringValue(config, "workingDir");
        if (workingDir != null) agentBuilder.workingDir(workingDir);

        var policy = stringValue(config, "policy");
        if (policy != null) agentBuilder.policy(WorkingDirPolicy.valueOf(policy.toUpperCase().replace('-', '_')));

        var poolBuilder = agentBuilder.pool();

        var minActive = intValue(config, "pool.minActive");
        if (minActive != null) poolBuilder.minActive(minActive);

        var maxActive = intValue(config, "pool.maxActive");
        if (maxActive != null) poolBuilder.maxActive(maxActive);

        var eviction = stringValue(config, "pool.eviction");
        if (eviction != null) poolBuilder.eviction(EvictionStrategy.valueOf(eviction.toUpperCase().replace('-', '_')));

        return poolBuilder.build();
    }

    public static Map<String, Object> toProviderConfig(AgentPoolDefinition def) {
        var map = new LinkedHashMap<String, Object>();

        if (def.agent().command() != null) map.put("command", def.agent().command());
        if (def.agent().workingDir() != null) map.put("workingDir", def.agent().workingDir());
        map.put("policy", def.agent().policy().name());

        map.put("pool.minActive", def.pool().minActive());
        map.put("pool.maxActive", def.pool().maxActive());
        map.put("pool.eviction", def.pool().eviction().name());

        return map;
    }

    private static String stringValue(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private static Integer intValue(Map<String, Object> map, String key) {
        var value = map.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        return null;
    }
}
