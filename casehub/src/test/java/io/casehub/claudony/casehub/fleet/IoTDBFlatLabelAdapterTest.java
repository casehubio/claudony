package io.casehub.claudony.casehub.fleet;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IoTDBFlatLabelAdapterTest {

    private SimpleMeterRegistry registry;
    private ArrayList<String> insertedSql;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        insertedSql = new ArrayList<>();
    }

    private AgentSessionManager stubManager(int min, int max) {
        var count = new AtomicInteger();
        return new AgentSessionManager(
            new AgentSessionManagerConfig(min, max),
            new SessionOperations() {
                @Override public String create(String i, String w) { return "s-" + count.incrementAndGet(); }
                @Override public String conversationId(String s) { return "c-" + s; }
                @Override public void suspend(String s) {}
                @Override public void resume(String s, String c, String w) {}
                @Override public void destroy(String s) {}
                @Override public long memoryBytes(String s) { return 0; }
            }
        );
    }

    @Test
    void tick_readsGaugesAndProducesSql() {
        var mgrRegistry = new AgentPoolManagerRegistry();
        var mgr = stubManager(0, 10);
        mgrRegistry.register("default", mgr);

        var metricsRegistrar = new PoolMetricsRegistrar(registry, mgrRegistry);
        metricsRegistrar.registerPool("default");
        mgr.acquireSession("w1", "/tmp");

        var adapter = new IoTDBFlatLabelAdapter(registry, insertedSql::add);
        adapter.tick();

        assertThat(insertedSql).hasSize(1);
        assertThat(insertedSql.get(0)).contains("pool_metrics");
        assertThat(insertedSql.get(0)).contains("'default'");
        assertThat(insertedSql.get(0)).contains(", 1,");
    }

    @Test
    void tick_withNoMetrics_noInsert() {
        var adapter = new IoTDBFlatLabelAdapter(registry, insertedSql::add);
        adapter.tick();
        assertThat(insertedSql).isEmpty();
    }

    @Test
    void tick_multiplePoolsProduceMultipleInserts() {
        var mgrRegistry = new AgentPoolManagerRegistry();
        var mgr1 = stubManager(0, 10);
        var mgr2 = stubManager(0, 5);
        mgrRegistry.register("default", mgr1);
        mgrRegistry.register("review", mgr2);

        var metricsRegistrar = new PoolMetricsRegistrar(registry, mgrRegistry);
        metricsRegistrar.registerPool("default");
        metricsRegistrar.registerPool("review");

        var adapter = new IoTDBFlatLabelAdapter(registry, insertedSql::add);
        adapter.tick();

        assertThat(insertedSql).hasSize(2);
        assertThat(insertedSql).anyMatch(sql -> sql.contains("'default'"));
        assertThat(insertedSql).anyMatch(sql -> sql.contains("'review'"));
    }

    @Test
    void tick_fillRatioFormattedWithDecimals() {
        var mgrRegistry = new AgentPoolManagerRegistry();
        var mgr = stubManager(0, 10);
        mgrRegistry.register("default", mgr);

        var metricsRegistrar = new PoolMetricsRegistrar(registry, mgrRegistry);
        metricsRegistrar.registerPool("default");
        mgr.acquireSession("w1", "/tmp");
        mgr.acquireSession("w2", "/tmp2");
        mgr.acquireSession("w3", "/tmp3");

        var adapter = new IoTDBFlatLabelAdapter(registry, insertedSql::add);
        adapter.tick();

        assertThat(insertedSql.get(0)).contains("0.3000");
    }
}
