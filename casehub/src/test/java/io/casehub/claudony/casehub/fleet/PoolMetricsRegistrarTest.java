package io.casehub.claudony.casehub.fleet;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PoolMetricsRegistrarTest {

    private MeterRegistry registry;
    private AgentPoolManagerRegistry mgrRegistry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        mgrRegistry = new AgentPoolManagerRegistry();
    }

    private AgentSessionManager stubManager(int min, int max) {
        return new AgentSessionManager(
            new AgentSessionManagerConfig(min, max),
            new SessionOperations() {
                @Override public String create(String i, String w) { return "s-1"; }
                @Override public String conversationId(String s) { return "c-1"; }
                @Override public void suspend(String s) {}
                @Override public void resume(String s, String c, String w) {}
                @Override public void destroy(String s) {}
                @Override public long memoryBytes(String s) { return 0; }
            }
        );
    }

    @Test
    void registerPool_createsGauges() {
        var mgr = stubManager(0, 10);
        mgrRegistry.register("default", mgr);
        var registrar = new PoolMetricsRegistrar(registry, mgrRegistry);
        registrar.registerPool("default");

        assertThat(registry.get("claudony.pool.active").tag("pool", "default").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("claudony.pool.max").tag("pool", "default").gauge().value()).isEqualTo(10.0);
    }

    @Test
    void gaugesReflectLiveState() {
        var mgr = stubManager(0, 10);
        mgrRegistry.register("default", mgr);
        var registrar = new PoolMetricsRegistrar(registry, mgrRegistry);
        registrar.registerPool("default");

        mgr.acquireSession("worker1", "/tmp");

        assertThat(registry.get("claudony.pool.active").tag("pool", "default").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("claudony.pool.fill_ratio").tag("pool", "default").gauge().value())
            .isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void incrementAcquireCounter() {
        var mgr = stubManager(0, 10);
        mgrRegistry.register("default", mgr);
        var registrar = new PoolMetricsRegistrar(registry, mgrRegistry);
        registrar.registerPool("default");

        registrar.recordAcquire("default");

        assertThat(registry.get("claudony.pool.acquires.total").tag("pool", "default").counter().count()).isEqualTo(1.0);
    }

    @Test
    void incrementEvictionCounter() {
        var mgr = stubManager(0, 10);
        mgrRegistry.register("default", mgr);
        var registrar = new PoolMetricsRegistrar(registry, mgrRegistry);
        registrar.registerPool("default");

        registrar.recordEviction("default");
        registrar.recordEviction("default");

        assertThat(registry.get("claudony.pool.evictions.total").tag("pool", "default").counter().count()).isEqualTo(2.0);
    }

    @Test
    void incrementExhaustionCounter() {
        var mgr = stubManager(0, 10);
        mgrRegistry.register("default", mgr);
        var registrar = new PoolMetricsRegistrar(registry, mgrRegistry);
        registrar.registerPool("default");

        registrar.recordExhaustion("default");

        assertThat(registry.get("claudony.pool.exhaustions.total").tag("pool", "default").counter().count()).isEqualTo(1.0);
    }
}
