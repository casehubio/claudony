package io.casehub.claudony.testing.fleet;

import io.casehub.claudony.casehub.fleet.AgentPoolExhaustedException;
import io.casehub.claudony.casehub.fleet.PoolAtCapacityException;
import io.casehub.claudony.casehub.fleet.AgentPoolHealth;
import io.casehub.claudony.casehub.fleet.AgentSessionManager;
import io.casehub.claudony.casehub.fleet.AgentSessionManagerConfig;
import io.casehub.claudony.casehub.fleet.SessionState;
import io.casehub.claudony.casehub.fleet.WorkingDirConflictException;
import io.casehub.claudony.casehub.fleet.WorkingDirPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSessionManagerWithTestPoolTest {

    private InMemorySessionOperations ops;

    @BeforeEach
    void setUp() {
        ops = new InMemorySessionOperations();
    }

    private AgentSessionManager createManager(int minActive, int maxActive) {
        return new AgentSessionManager(new AgentSessionManagerConfig(minActive, maxActive), ops);
    }

    @Test
    void acquireSession_createsNewWhenNoneExist() {
        var manager = createManager(0, 5);
        var session = manager.acquireSession("reviewer", "/workspace/pr-42");
        assertThat(session.identity()).isEqualTo("reviewer");
        assertThat(session.workingDir()).isEqualTo("/workspace/pr-42");
        assertThat(session.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(ops.createCount()).isEqualTo(1);
    }

    @Test
    void acquireSession_resumesSuspendedMatchingIdentity() {
        var manager = createManager(0, 5);
        var session = manager.acquireSession("reviewer", "/workspace/pr-42");
        String originalId = session.instanceId();

        manager.suspendSession(originalId);
        assertThat(ops.isSuspended(originalId)).isTrue();

        var resumed = manager.acquireSession("reviewer", "/workspace/pr-42");
        assertThat(resumed.instanceId()).isEqualTo(originalId);
        assertThat(resumed.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(ops.resumeCount()).isEqualTo(1);
    }

    @Test
    void acquireSession_preservesConversationIdAcrossSuspendResume() {
        var manager = createManager(0, 5);
        var session = manager.acquireSession("reviewer", "/workspace/pr-42");
        String originalConvId = session.conversationId();

        manager.suspendSession(session.instanceId());
        var resumed = manager.acquireSession("reviewer", "/workspace/pr-42");
        assertThat(resumed.conversationId()).isEqualTo(originalConvId);
    }

    @Test
    void acquireSession_exclusivePolicy_throwsOnConflict() {
        var manager = createManager(0, 5);
        manager.acquireSession("reviewer", "/workspace/pr-42");

        assertThatThrownBy(() -> manager.acquireSession("coder", "/workspace/pr-42",
                null, WorkingDirPolicy.EXCLUSIVE))
                .isInstanceOf(WorkingDirConflictException.class);
    }

    @Test
    void acquireSession_sharedReadPolicy_allowsSameWorkingDir() {
        var manager = createManager(0, 5);
        manager.acquireSession("reviewer", "/workspace/pr-42", null, WorkingDirPolicy.SHARED_READ);

        assertThatCode(() -> manager.acquireSession("coder", "/workspace/pr-42",
                null, WorkingDirPolicy.SHARED_READ))
                .doesNotThrowAnyException();
        assertThat(manager.activeCount()).isEqualTo(2);
    }

    @Test
    void eviction_suspendsHighestScoring() throws Exception {
        var manager = createManager(0, 2);
        var s1 = manager.acquireSession("reviewer", "/workspace/pr-1");
        manager.recordInteraction(s1.instanceId(), 50 * 1024 * 1024);

        Thread.sleep(10);
        var s2 = manager.acquireSession("coder", "/workspace/task-1");
        manager.recordInteraction(s2.instanceId(), 50 * 1024 * 1024);

        manager.acquireSession("tester", "/workspace/test-1");
        assertThat(manager.activeCount()).isEqualTo(2);
        assertThat(manager.suspendedCount()).isEqualTo(1);
        assertThat(ops.suspendCount()).isEqualTo(1);
        assertThat(ops.suspendedSessions()).hasSize(1);
    }

    @Test
    void exhausted_throwsWhenMinPreventsEviction() {
        var manager = createManager(2, 2);
        manager.acquireSession("reviewer", "/workspace/pr-1");
        manager.acquireSession("coder", "/workspace/task-1");

        assertThatThrownBy(() -> manager.acquireSession("tester", "/workspace/test-1"))
                .isInstanceOf(PoolAtCapacityException.class);
    }

    @Test
    void shutdown_destroysAll() {
        var manager = createManager(0, 5);
        manager.acquireSession("reviewer", "/workspace/pr-1");
        manager.acquireSession("coder", "/workspace/task-1");
        var s3 = manager.acquireSession("tester", "/workspace/test-1");
        manager.suspendSession(s3.instanceId());

        manager.shutdown();
        assertThat(manager.activeCount()).isZero();
        assertThat(ops.destroyCount()).isEqualTo(3);
    }

    @Test
    void status_returnsCorrectCounts() {
        var manager = createManager(1, 5);
        manager.acquireSession("reviewer", "/workspace/pr-1");
        manager.acquireSession("coder", "/workspace/task-1");
        var s3 = manager.acquireSession("tester", "/workspace/test-1");
        manager.suspendSession(s3.instanceId());

        var status = manager.status();
        assertThat(status.active()).isEqualTo(2);
        assertThat(status.idle()).isEqualTo(1);
        assertThat(status.total()).isEqualTo(3);
        assertThat(status.health()).isEqualTo(AgentPoolHealth.HEALTHY);
    }

    @Test
    void testPoolBuilder_fullLifecycleWithInspection() {
        var pool = TestPoolBuilder.create().maxActive(2).build();

        pool.manager().acquireSession("worker-1", "/workspace/a");
        pool.manager().acquireSession("worker-2", "/workspace/b");
        assertThat(pool.ops().createCount()).isEqualTo(2);

        pool.manager().acquireSession("worker-3", "/workspace/c");
        assertThat(pool.ops().suspendCount()).isEqualTo(1);
        assertThat(pool.ops().suspendedSessions()).hasSize(1);
        assertThat(pool.ops().activeSessions()).hasSize(2);
    }
}
