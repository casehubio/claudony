package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.api.model.ModelChain;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative definition of an agent pool: agent identity/config and pool capacity.
 * Immutable once built. Use {@link #builder()} for the fluent DSL.
 *
 * <pre>{@code
 * AgentPoolDefinition def = AgentPoolDefinition.builder()
 *     .agent("code-reviewer")
 *         .workingDir("/workspace/reviews")
 *         .policy(WorkingDirPolicy.SHARED_READ)
 *         .command("claude --model opus")
 *     .pool()
 *         .minActive(2)
 *         .maxActive(10)
 *     .build();
 * }</pre>
 */
public final class AgentPoolDefinition {

    private final AgentConfig agent;
    private final PoolConfig pool;

    AgentPoolDefinition(AgentConfig agent, PoolConfig pool) {
        this.agent = Objects.requireNonNull(agent);
        this.pool = Objects.requireNonNull(pool);
    }

    public AgentConfig agent() { return agent; }
    public PoolConfig pool() { return pool; }

    public AgentSessionManagerConfig toSessionManagerConfig() {
        return new AgentSessionManagerConfig(pool.minActive(), pool.maxActive());
    }

    public static Builder builder() {
        return new Builder();
    }

    public record AgentConfig(String name, String workingDir, WorkingDirPolicy policy, String command,
                               ModelChain modelChain, Map<String, String> entryCommands,
                               Map<String, Duration> gracePeriods) {
        public AgentConfig {
            Objects.requireNonNull(name, "agent name is required");
            if (name.isBlank()) throw new IllegalArgumentException("agent name must not be blank");
            if (policy == null) policy = WorkingDirPolicy.EXCLUSIVE;
            if (entryCommands == null) entryCommands = Map.of();
            if (gracePeriods == null) gracePeriods = Map.of();
        }

        public AgentConfig(String name, String workingDir, WorkingDirPolicy policy, String command) {
            this(name, workingDir, policy, command, null, Map.of(), Map.of());
        }
    }

    public record PoolConfig(int minActive, int maxActive, EvictionStrategy eviction, ScalingConfig scaling,
                             BudgetConfig budget) {
        public PoolConfig {
            if (minActive < 0) {throw new IllegalArgumentException("minActive must be >= 0");}
            if (maxActive < 1) {throw new IllegalArgumentException("maxActive must be >= 1");}
            if (maxActive < minActive) {throw new IllegalArgumentException("maxActive must be >= minActive");}
            if (eviction == null) {eviction = EvictionStrategy.MEMORY_WEIGHTED;}
            if (scaling == null) {scaling = ScalingConfig.NoScalingConfig.INSTANCE;}
        }

        public PoolConfig(int minActive, int maxActive, EvictionStrategy eviction, ScalingConfig scaling) {
            this(minActive, maxActive, eviction, scaling, null);
        }
    }

    public static final class Builder {

        private String agentName;
        private String workingDir;
        private WorkingDirPolicy policy;
        private String command;
        private ModelChain modelChain;
        private Map<String, String> entryCommands;
        private Map<String, Duration> gracePeriods;
        private int minActive = 0;
        private int maxActive = 10;
        private EvictionStrategy eviction;
        private ScalingConfig    scaling;
        private BudgetConfig     budget;


        private Builder() {}

        public AgentBuilder agent(String name) {
            this.agentName = name;
            return new AgentBuilder(this);
        }

        public AgentPoolDefinition build() {
            var agentConfig = new AgentConfig(agentName, workingDir, policy, command, modelChain, entryCommands, gracePeriods);
            var poolConfig  = new PoolConfig(minActive, maxActive, eviction, scaling, budget);
            return new AgentPoolDefinition(agentConfig, poolConfig);
        }
    }

    public static final class AgentBuilder {

        private final Builder parent;

        AgentBuilder(Builder parent) {
            this.parent = parent;
        }

        public AgentBuilder workingDir(String workingDir) {
            parent.workingDir = workingDir;
            return this;
        }

        public AgentBuilder policy(WorkingDirPolicy policy) {
            parent.policy = policy;
            return this;
        }

        public AgentBuilder command(String command) {
            parent.command = command;
            return this;
        }

        public AgentBuilder modelChain(ModelChain modelChain) {
            parent.modelChain = modelChain;
            return this;
        }

        public AgentBuilder entryCommands(Map<String, String> entryCommands) {
            parent.entryCommands = entryCommands;
            return this;
        }

        public AgentBuilder gracePeriods(Map<String, Duration> gracePeriods) {
            parent.gracePeriods = gracePeriods;
            return this;
        }

        public PoolBuilder pool() {
            return new PoolBuilder(parent);
        }

        public AgentBuilder agent(String name) {
            parent.agentName = name;
            parent.workingDir = null;
            parent.policy = null;
            parent.command = null;
            return this;
        }

        public AgentPoolDefinition build() {
            return parent.build();
        }
    }

    public static final class PoolBuilder {

        private final Builder parent;

        PoolBuilder(Builder parent) {
            this.parent = parent;
        }

        public PoolBuilder minActive(int minActive) {
            parent.minActive = minActive;
            return this;
        }

        public PoolBuilder maxActive(int maxActive) {
            parent.maxActive = maxActive;
            return this;
        }

        public PoolBuilder eviction(EvictionStrategy eviction) {
            parent.eviction = eviction;
            return this;
        }

        public PoolBuilder scaling(ScalingConfig scaling) {
            parent.scaling = scaling;
            return this;
        }

        public PoolBuilder budget(BudgetConfig budget) {
            parent.budget = budget;
            return this;
        }


        public AgentBuilder agent(String name) {
            parent.agentName = name;
            parent.workingDir = null;
            parent.policy = null;
            parent.command = null;
            return new AgentBuilder(parent);
        }

        public AgentPoolDefinition build() {
            return parent.build();
        }
    }
}
