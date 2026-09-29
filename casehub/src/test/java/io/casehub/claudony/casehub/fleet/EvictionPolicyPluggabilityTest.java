package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvictionPolicyPluggabilityTest {

    private AtomicInteger createCount;
    private ConcurrentHashMap<String, String> conversationIds;

    @BeforeEach
    void setUp() {
        createCount = new AtomicInteger();
        conversationIds = new ConcurrentHashMap<>();
    }

    private SessionOperations stubOps() {
        return new SessionOperations() {
            @Override
            public String create(String identity, String workingDir) {
                String id = "session-" + createCount.incrementAndGet();
                conversationIds.put(id, "conv-" + id);
                return id;
            }

            @Override
            public String conversationId(String sessionId) {
                return conversationIds.get(sessionId);
            }

            @Override
            public void suspend(String sessionId) { }

            @Override
            public void resume(String sessionId, String conversationId, String workingDir) { }

            @Override
            public void destroy(String sessionId) { }

            @Override
            public long memoryBytes(String sessionId) { return 0; }
        };
    }

    @Test
    void customPolicyDeterminesEvictionOrder() {
        // Policy that always evicts the session with identity "expendable"
        EvictionPolicy expendableFirst = (session, now) ->
                session.identity().equals("expendable") ? Double.MAX_VALUE : 0.0;

        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 2),
                stubOps(),
                expendableFirst
        );

        var keeper = manager.acquireSession("keeper", "/ws1");
        var expendable = manager.acquireSession("expendable", "/ws2");

        // Trigger eviction — custom policy should evict "expendable" regardless of timing/memory
        var newSession = manager.acquireSession("newcomer", "/ws3");

        assertThat(expendable.state()).isEqualTo(SessionState.SUSPENDED);
        assertThat(keeper.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(newSession.state()).isEqualTo(SessionState.ACTIVE);
    }

    @Test
    void defaultConstructorUsesDefaultPolicy() {
        // The two-arg constructor (no explicit policy) should still work
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 2),
                stubOps()
        );

        var s1 = manager.acquireSession("reviewer", "/ws1");
        manager.recordInteraction(s1.instanceId(), 50 * 1024 * 1024);
        Thread.currentThread(); // no sleep — just ensure s2 is created after
        var s2 = manager.acquireSession("coder", "/ws2");
        manager.recordInteraction(s2.instanceId(), 50 * 1024 * 1024);

        // Should still evict without error (using DefaultEvictionPolicy)
        var s3 = manager.acquireSession("tester", "/ws3");
        assertThat(manager.activeCount()).isEqualTo(2);
        assertThat(manager.suspendedCount()).isEqualTo(1);
    }
}
