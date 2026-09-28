package io.casehub.claudony.testing.fleet;

import io.casehub.claudony.casehub.fleet.AgentSessionManager;
import io.casehub.claudony.casehub.fleet.AgentSessionManagerConfig;

public final class TestPoolBuilder {

    private int minActive = 0;
    private int maxActive = 10;

    private TestPoolBuilder() {}

    public static TestPoolBuilder create() {
        return new TestPoolBuilder();
    }

    public TestPoolBuilder minActive(int minActive) {
        this.minActive = minActive;
        return this;
    }

    public TestPoolBuilder maxActive(int maxActive) {
        this.maxActive = maxActive;
        return this;
    }

    public TestPool build() {
        var ops = new InMemorySessionOperations();
        var config = new AgentSessionManagerConfig(minActive, maxActive);
        var manager = new AgentSessionManager(config, ops);
        return new TestPool(manager, ops);
    }
}
