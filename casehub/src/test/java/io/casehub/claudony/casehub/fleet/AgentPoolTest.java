package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPoolTest {

    private AtomicInteger createCount;
    private AtomicInteger destroyCount;
    private AgentPool pool;

    @BeforeEach
    void setUp() {
        createCount = new AtomicInteger();
        destroyCount = new AtomicInteger();
    }

    private AgentPool createPool(int min, int max) {
        return new AgentPool(
                new AgentPoolConfig(min, max, Duration.ofMinutes(15), Duration.ofSeconds(10)),
                () -> "session-" + createCount.incrementAndGet(),
                sessionId -> destroyCount.incrementAndGet()
        );
    }

    @Test
    void preWarm_createsMinSessions() {
        pool = createPool(3, 10);
        pool.preWarm();
        var status = pool.status();
        assertThat(status.idle()).isEqualTo(3);
        assertThat(status.active()).isZero();
        assertThat(status.total()).isEqualTo(3);
        assertThat(createCount.get()).isEqualTo(3);
    }

    @Test
    void acquire_returnsIdleSession() {
        pool = createPool(1, 5);
        pool.preWarm();
        String sessionId = pool.acquire();
        assertThat(sessionId).isNotNull();
        assertThat(sessionId).startsWith("session-");
        var status = pool.status();
        assertThat(status.active()).isEqualTo(1);
        assertThat(status.idle()).isZero();
    }

    @Test
    void acquire_createsOnDemandWhenNoIdle() {
        pool = createPool(0, 5);
        String sessionId = pool.acquire();
        assertThat(sessionId).isNotNull();
        assertThat(createCount.get()).isEqualTo(1);
        var status = pool.status();
        assertThat(status.active()).isEqualTo(1);
        assertThat(status.idle()).isZero();
    }

    @Test
    void acquire_throwsWhenAtMaxCapacity() {
        pool = createPool(0, 2);
        pool.acquire();
        pool.acquire();
        assertThatThrownBy(() -> pool.acquire())
                .isInstanceOf(AgentPoolExhaustedException.class);
    }

    @Test
    void release_destroysAndReplacesWhenBelowMin() {
        pool = createPool(2, 5);
        pool.preWarm();
        int createdDuringPreWarm = createCount.get();

        String sessionId = pool.acquire();
        pool.release(sessionId);

        assertThat(destroyCount.get()).isEqualTo(1);
        assertThat(createCount.get()).isEqualTo(createdDuringPreWarm + 1);
        var status = pool.status();
        assertThat(status.active()).isZero();
        assertThat(status.idle()).isEqualTo(2);
    }

    @Test
    void release_destroysWithoutReplacingWhenAtOrAboveMin() {
        pool = createPool(0, 5);
        String sessionId = pool.acquire();
        pool.release(sessionId);
        assertThat(destroyCount.get()).isEqualTo(1);
        var status = pool.status();
        assertThat(status.idle()).isZero();
        assertThat(status.total()).isZero();
    }

    @Test
    void release_unknownSessionIsNoOp() {
        pool = createPool(0, 5);
        assertThatCode(() -> pool.release("unknown-session"))
                .doesNotThrowAnyException();
    }

    @Test
    void status_healthyWhenFunctional() {
        pool = createPool(0, 5);
        assertThat(pool.status().health()).isEqualTo(AgentPoolHealth.HEALTHY);
    }

    @Test
    void multipleAcquireAndRelease_tracksCorrectly() {
        pool = createPool(0, 3);
        String s1 = pool.acquire();
        String s2 = pool.acquire();

        assertThat(pool.status().active()).isEqualTo(2);
        assertThat(pool.status().total()).isEqualTo(2);

        pool.release(s1);
        assertThat(pool.status().active()).isEqualTo(1);

        pool.release(s2);
        assertThat(pool.status().active()).isZero();
    }

    @Test
    void preWarm_withZeroMin_doesNothing() {
        pool = createPool(0, 5);
        pool.preWarm();
        assertThat(createCount.get()).isZero();
        assertThat(pool.status().total()).isZero();
    }

    @Test
    void shutdown_destroysAllSessions() {
        pool = createPool(2, 5);
        pool.preWarm();
        pool.acquire();
        // 2 pre-warmed, 1 acquired from idle → 1 active + 1 idle = 2 total
        pool.shutdown();

        assertThat(destroyCount.get()).isEqualTo(2);
        var status = pool.status();
        assertThat(status.total()).isZero();
    }
}
