package io.casehub.claudony.testing.fleet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestPoolBuilderTest {

    @Test
    void build_returnsTestPoolWithManagerAndOps() {
        var pool = TestPoolBuilder.create().build();
        assertThat(pool.manager()).isNotNull();
        assertThat(pool.ops()).isNotNull();
    }

    @Test
    void build_usesDefaultConfig() {
        var pool = TestPoolBuilder.create().build();
        var status = pool.manager().status();
        assertThat(status.min()).isZero();
        assertThat(status.max()).isEqualTo(10);
    }

    @Test
    void build_customMinMax() {
        var pool = TestPoolBuilder.create()
                .minActive(2)
                .maxActive(5)
                .build();
        var status = pool.manager().status();
        assertThat(status.min()).isEqualTo(2);
        assertThat(status.max()).isEqualTo(5);
    }

    @Test
    void pool_managerUsesInMemoryOps() {
        var pool = TestPoolBuilder.create().maxActive(5).build();
        pool.manager().acquireSession("worker-1", "/workspace/a");
        assertThat(pool.ops().createCount()).isEqualTo(1);
        assertThat(pool.ops().activeSessions()).hasSize(1);
    }

    @Test
    void pool_fullLifecycle() {
        var pool = TestPoolBuilder.create().maxActive(2).build();

        pool.manager().acquireSession("worker-1", "/workspace/a");
        pool.manager().acquireSession("worker-2", "/workspace/b");
        assertThat(pool.manager().activeCount()).isEqualTo(2);

        pool.manager().acquireSession("worker-3", "/workspace/c");
        assertThat(pool.manager().activeCount()).isEqualTo(2);
        assertThat(pool.ops().suspendCount()).isEqualTo(1);
        assertThat(pool.ops().suspendedSessions()).hasSize(1);
    }
}
