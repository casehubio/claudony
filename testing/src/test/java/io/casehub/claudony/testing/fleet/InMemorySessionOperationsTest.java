package io.casehub.claudony.testing.fleet;

import io.casehub.claudony.casehub.fleet.SessionOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySessionOperationsTest {

    private InMemorySessionOperations ops;

    @BeforeEach
    void setUp() {
        ops = new InMemorySessionOperations();
    }

    @Test
    void implementsSessionOperations() {
        assertThat(ops).isInstanceOf(SessionOperations.class);
    }

    @Test
    void create_returnsUniqueSessionIds() {
        String s1 = ops.create("worker-1", "/workspace/a");
        String s2 = ops.create("worker-2", "/workspace/b");
        assertThat(s1).isNotEqualTo(s2);
        assertThat(s1).startsWith("mem-");
        assertThat(ops.createCount()).isEqualTo(2);
    }

    @Test
    void create_storesConversationId() {
        String sessionId = ops.create("worker-1", "/workspace/a");
        assertThat(ops.conversationId(sessionId)).isNotNull();
    }

    @Test
    void create_withCommand_incrementsCounter() {
        String sessionId = ops.create("worker-1", "/workspace/a", "claude --model opus");
        assertThat(sessionId).startsWith("mem-");
        assertThat(ops.createCount()).isEqualTo(1);
    }

    @Test
    void suspend_movesToSuspendedMap() {
        String sessionId = ops.create("worker-1", "/workspace/a");
        assertThat(ops.isActive(sessionId)).isTrue();

        ops.suspend(sessionId);
        assertThat(ops.isActive(sessionId)).isFalse();
        assertThat(ops.isSuspended(sessionId)).isTrue();
        assertThat(ops.suspendCount()).isEqualTo(1);
    }

    @Test
    void resume_movesBackToActive() {
        String sessionId = ops.create("worker-1", "/workspace/a");
        ops.suspend(sessionId);

        ops.resume(sessionId, ops.conversationId(sessionId), "/workspace/a");
        assertThat(ops.isActive(sessionId)).isTrue();
        assertThat(ops.isSuspended(sessionId)).isFalse();
        assertThat(ops.resumeCount()).isEqualTo(1);
    }

    @Test
    void destroy_removesCompletely() {
        String sessionId = ops.create("worker-1", "/workspace/a");
        ops.destroy(sessionId);
        assertThat(ops.isActive(sessionId)).isFalse();
        assertThat(ops.isSuspended(sessionId)).isFalse();
        assertThat(ops.conversationId(sessionId)).isNull();
        assertThat(ops.destroyCount()).isEqualTo(1);
    }

    @Test
    void destroy_suspendedSession() {
        String sessionId = ops.create("worker-1", "/workspace/a");
        ops.suspend(sessionId);
        ops.destroy(sessionId);
        assertThat(ops.isSuspended(sessionId)).isFalse();
        assertThat(ops.destroyCount()).isEqualTo(1);
    }

    @Test
    void memoryBytes_returnsConfigurableDefault() {
        String sessionId = ops.create("worker-1", "/workspace/a");
        assertThat(ops.memoryBytes(sessionId)).isEqualTo(100 * 1024 * 1024L);

        ops.setDefaultMemoryBytes(50 * 1024 * 1024L);
        assertThat(ops.memoryBytes(sessionId)).isEqualTo(50 * 1024 * 1024L);
    }

    @Test
    void activeSessions_returnsCopy() {
        ops.create("worker-1", "/workspace/a");
        ops.create("worker-2", "/workspace/b");
        assertThat(ops.activeSessions()).hasSize(2);
    }

    @Test
    void suspendedSessions_returnsCopy() {
        String s1 = ops.create("worker-1", "/workspace/a");
        ops.suspend(s1);
        assertThat(ops.suspendedSessions()).hasSize(1);
        assertThat(ops.activeSessions()).isEmpty();
    }

    @Test
    void reset_clearsEverything() {
        ops.create("worker-1", "/workspace/a");
        ops.create("worker-2", "/workspace/b");
        ops.reset();
        assertThat(ops.activeSessions()).isEmpty();
        assertThat(ops.createCount()).isZero();
        assertThat(ops.suspendCount()).isZero();
    }

    @Test
    void conversationId_returnsNullForUnknown() {
        assertThat(ops.conversationId("nonexistent")).isNull();
    }

    @Test
    void threadSafety_concurrentCreates() throws Exception {
        var latch = new CountDownLatch(1);
        var futures = new ArrayList<Future<String>>();
        var executor = Executors.newFixedThreadPool(4);

        for (int i = 0; i < 20; i++) {
            int idx = i;
            futures.add(executor.submit(() -> {
                latch.await();
                return ops.create("worker-" + idx, "/workspace/" + idx);
            }));
        }
        latch.countDown();
        for (var f : futures) f.get();
        executor.shutdown();

        assertThat(ops.createCount()).isEqualTo(20);
        assertThat(ops.activeSessions()).hasSize(20);
    }
}
