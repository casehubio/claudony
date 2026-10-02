package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentPoolManagerRegistryTest {

    @Test
    void registerAndGet() {
        var registry = new AgentPoolManagerRegistry();
        var manager = new AgentSessionManager(
            new AgentSessionManagerConfig(0, 5), new StubOps());
        registry.register("reviewer", manager);
        assertThat(registry.get("reviewer")).isPresent().hasValue(manager);
    }

    @Test
    void getMissingReturnsEmpty() {
        var registry = new AgentPoolManagerRegistry();
        assertThat(registry.get("missing")).isEmpty();
    }

    @Test
    void poolNamesReturnsAll() {
        var registry = new AgentPoolManagerRegistry();
        registry.register("a", new AgentSessionManager(
            new AgentSessionManagerConfig(0, 5), new StubOps()));
        registry.register("b", new AgentSessionManager(
            new AgentSessionManagerConfig(0, 5), new StubOps()));
        assertThat(registry.poolNames()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void reverseIndexPopulated() {
        var registry = new AgentPoolManagerRegistry();
        registry.registerSession("session-1", "pool-a");
        assertThat(registry.poolNameForSession("session-1")).contains("pool-a");
    }

    @Test
    void reverseIndexCleaned() {
        var registry = new AgentPoolManagerRegistry();
        registry.registerSession("session-1", "pool-a");
        registry.deregisterSession("session-1");
        assertThat(registry.poolNameForSession("session-1")).isEmpty();
    }

    @Test
    void unknownSessionReturnsEmpty() {
        var registry = new AgentPoolManagerRegistry();
        assertThat(registry.poolNameForSession("nonexistent")).isEmpty();
    }


    private static class StubOps implements SessionOperations {
        @Override public String create(String i, String w) { return "s"; }
        @Override public String conversationId(String s) { return "c"; }
        @Override public void suspend(String s) {}
        @Override public void resume(String s, String c, String w) {}
        @Override public void destroy(String s) {}
        @Override public long memoryBytes(String s) { return 0; }
    }
}
