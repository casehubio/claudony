package io.casehub.claudony.casehub.fleet;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans classes for {@link PooledAgent} and {@link AgentPool} annotations
 * and converts them to {@link AgentPoolDefinition} instances via the fluent builder DSL.
 * Annotations are sugar over the builder — the scanner bridges between the two.
 */
public class AgentPoolAnnotationScanner {

    public List<AgentPoolDefinition> scan(Class<?>... classes) {
        var results = new ArrayList<AgentPoolDefinition>();
        for (Class<?> clazz : classes) {
            var pooledAgent = clazz.getAnnotation(PooledAgent.class);
            if (pooledAgent == null) continue;
            results.add(toDefinition(pooledAgent, clazz.getAnnotation(AgentPool.class)));
        }
        return results;
    }

    public void scanInto(AgentPoolDefinitionRegistry registry, Class<?>... classes) {
        for (var definition : scan(classes)) {
            registry.register(definition);
        }
    }

    AgentPoolDefinition toDefinition(PooledAgent agent, AgentPool pool) {
        var agentBuilder = addAgentConfig(AgentPoolDefinition.builder().agent(agent.name()), agent);

        if (pool != null) {
            return agentBuilder.pool()
                    .minActive(pool.minActive())
                    .maxActive(pool.maxActive())
                    .eviction(pool.eviction())
                    .build();
        }

        return agentBuilder.build();
    }

    private AgentPoolDefinition.AgentBuilder addAgentConfig(
            AgentPoolDefinition.AgentBuilder builder, PooledAgent agent) {
        if (!agent.workingDir().isEmpty()) {
            builder = builder.workingDir(agent.workingDir());
        }
        builder = builder.policy(agent.policy());
        if (!agent.command().isEmpty()) {
            builder = builder.command(agent.command());
        }
        return builder;
    }
}
