package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class AgentSessionManagerTest {

    private AtomicInteger createCount;
    private AtomicInteger suspendCount;
    private AtomicInteger resumeCount;
    private AtomicInteger destroyCount;
    private ConcurrentHashMap<String, String> conversationIds;
    private AgentSessionManager manager;

    @BeforeEach
    void setUp() {
        createCount = new AtomicInteger();
        suspendCount = new AtomicInteger();
        resumeCount = new AtomicInteger();
        destroyCount = new AtomicInteger();
        conversationIds = new ConcurrentHashMap<>();
    }

    private AgentSessionManager createManager(int minActive, int maxActive) {
        return new AgentSessionManager(
                new AgentSessionManagerConfig(minActive, maxActive),
                new SessionOperations() {
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
                    public void suspend(String sessionId) {
                        suspendCount.incrementAndGet();
                    }

                    @Override
                    public void resume(String sessionId, String conversationId, String workingDir) {
                        resumeCount.incrementAndGet();
                    }

                    @Override
                    public void destroy(String sessionId) {
                        destroyCount.incrementAndGet();
                    }

                    @Override
                    public long memoryBytes(String sessionId) {
                        return 100 * 1024 * 1024; // 100MB default
                    }
                }
        );
    }

    @Test
    void acquireSession_createsNewWhenNoneExist() {
        manager = createManager(0, 5);
        var session = manager.acquireSession("reviewer", "/workspace/pr-42");
        assertThat(session).isNotNull();
        assertThat(session.identity()).isEqualTo("reviewer");
        assertThat(session.workingDir()).isEqualTo("/workspace/pr-42");
        assertThat(session.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(manager.activeCount()).isEqualTo(1);
        assertThat(createCount.get()).isEqualTo(1);
    }

    @Test
    void acquireSession_resumesSuspendedMatchingIdentity() {
        manager = createManager(0, 5);
        var session = manager.acquireSession("reviewer", "/workspace/pr-42");
        String originalId = session.instanceId();

        manager.suspendSession(originalId);
        assertThat(manager.activeCount()).isZero();
        assertThat(manager.suspendedCount()).isEqualTo(1);

        var resumed = manager.acquireSession("reviewer", "/workspace/pr-42");
        assertThat(resumed.instanceId()).isEqualTo(originalId);
        assertThat(resumed.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(resumeCount.get()).isEqualTo(1);
    }

    @Test
    void acquireSession_doesNotResumeMismatchedIdentity() {
        manager = createManager(0, 5);
        manager.acquireSession("reviewer", "/workspace/pr-42");
        manager.suspendSession("session-1");

        var newSession = manager.acquireSession("coder", "/workspace/task-1");
        assertThat(newSession.instanceId()).isNotEqualTo("session-1");
        assertThat(createCount.get()).isEqualTo(2);
    }

    @Test
    void acquireSession_evictsHighestScoringWhenAtMaxActive() throws Exception {
        manager = createManager(0, 2);
        var s1 = manager.acquireSession("reviewer", "/workspace/pr-1");
        manager.recordInteraction(s1.instanceId(), 50 * 1024 * 1024);

        Thread.sleep(10);
        var s2 = manager.acquireSession("coder", "/workspace/task-1");
        manager.recordInteraction(s2.instanceId(), 50 * 1024 * 1024);

        // s1 has been idle longer → higher eviction score → gets suspended
        var s3 = manager.acquireSession("tester", "/workspace/test-1");
        assertThat(manager.activeCount()).isEqualTo(2);
        assertThat(manager.suspendedCount()).isEqualTo(1);
        assertThat(suspendCount.get()).isEqualTo(1);
    }

    @Test
    void acquireSession_throwsWhenAtMaxAndMinPreventsEviction() {
        manager = createManager(2, 2);
        manager.acquireSession("reviewer", "/workspace/pr-1");
        manager.acquireSession("coder", "/workspace/task-1");

        // at max, minActive = max → can't evict
        assertThatThrownBy(() -> manager.acquireSession("tester", "/workspace/test-1"))
                .isInstanceOf(AgentPoolExhaustedException.class);
    }

    @Test
    void suspendSession_movesToSuspended() {
        manager = createManager(0, 5);
        var session = manager.acquireSession("reviewer", "/workspace/pr-42");
        manager.suspendSession(session.instanceId());

        assertThat(manager.activeCount()).isZero();
        assertThat(manager.suspendedCount()).isEqualTo(1);
        assertThat(suspendCount.get()).isEqualTo(1);
    }

    @Test
    void suspendSession_unknownIdIsNoOp() {
        manager = createManager(0, 5);
        assertThatCode(() -> manager.suspendSession("nonexistent"))
                .doesNotThrowAnyException();
    }

    @Test
    void resumeSession_byInstanceId() {
        manager = createManager(0, 5);
        var session = manager.acquireSession("reviewer", "/workspace/pr-42");
        manager.suspendSession(session.instanceId());

        var resumed = manager.resumeSession(session.instanceId());
        assertThat(resumed).isNotNull();
        assertThat(resumed.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(manager.activeCount()).isEqualTo(1);
        assertThat(manager.suspendedCount()).isZero();
    }

    @Test
    void resumeSession_unknownIdReturnsNull() {
        manager = createManager(0, 5);
        assertThat(manager.resumeSession("nonexistent")).isNull();
    }

    @Test
    void recordInteraction_updatesEvictionMetadata() throws Exception {
        manager = createManager(0, 2);
        var s1 = manager.acquireSession("reviewer", "/workspace/pr-1");
        Thread.sleep(10);
        var s2 = manager.acquireSession("coder", "/workspace/task-1");

        // s1 is older but uses much less memory
        manager.recordInteraction(s1.instanceId(), 10 * 1024 * 1024);
        manager.recordInteraction(s2.instanceId(), 500 * 1024 * 1024);

        // s2 has higher memory → higher eviction score despite being newer
        var s3 = manager.acquireSession("tester", "/workspace/test-1");

        // s2 was evicted (high memory outweighs recency)
        var s2Managed = manager.getSession(s2.instanceId());
        assertThat(s2Managed.state()).isEqualTo(SessionState.SUSPENDED);
    }

    @Test
    void destroySession_removesCompletely() {
        manager = createManager(0, 5);
        var session = manager.acquireSession("reviewer", "/workspace/pr-42");
        manager.destroySession(session.instanceId());

        assertThat(manager.activeCount()).isZero();
        assertThat(manager.suspendedCount()).isZero();
        assertThat(destroyCount.get()).isEqualTo(1);
    }

    @Test
    void shutdown_destroysAll() {
        manager = createManager(0, 5);
        manager.acquireSession("reviewer", "/workspace/pr-1");
        manager.acquireSession("coder", "/workspace/task-1");
        var s3 = manager.acquireSession("tester", "/workspace/test-1");
        manager.suspendSession(s3.instanceId());

        manager.shutdown();

        assertThat(manager.activeCount()).isZero();
        assertThat(manager.suspendedCount()).isZero();
        // 2 active destroyed + 1 suspended destroyed = 3, but suspended was already
        // "suspended" (tmux killed) so destroy just cleans metadata. Still 3 destroy calls.
        assertThat(destroyCount.get()).isEqualTo(3);
    }

    @Test
    void status_returnsCorrectCounts() {
        manager = createManager(1, 5);
        manager.acquireSession("reviewer", "/workspace/pr-1");
        manager.acquireSession("coder", "/workspace/task-1");
        var s3 = manager.acquireSession("tester", "/workspace/test-1");
        manager.suspendSession(s3.instanceId());

        var status = manager.status();
        assertThat(status.active()).isEqualTo(2);
        assertThat(status.idle()).isEqualTo(1); // suspended
        assertThat(status.total()).isEqualTo(3);
        assertThat(status.health()).isEqualTo(AgentPoolHealth.HEALTHY);
    }
}
