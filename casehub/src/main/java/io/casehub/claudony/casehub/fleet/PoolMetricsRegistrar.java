package io.casehub.claudony.casehub.fleet;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PoolMetricsRegistrar {

    private final MeterRegistry registry;
    private final AgentPoolManagerRegistry mgrRegistry;
    private final Map<String, PoolCounters> countersByPool = new ConcurrentHashMap<>();

    @Inject
    public PoolMetricsRegistrar(MeterRegistry registry, AgentPoolManagerRegistry mgrRegistry) {
        this.registry = registry;
        this.mgrRegistry = mgrRegistry;
    }

    public void registerPool(String poolName) {
        var tags = Tags.of("pool", poolName);
        var mgr = mgrRegistry.get(poolName).orElseThrow(
            () -> new IllegalArgumentException("Pool not found: " + poolName));

        registry.gauge("claudony.pool.active", tags, mgr, m -> m.activeCount());
        registry.gauge("claudony.pool.idle", tags, mgr, m -> m.suspendedCount());
        registry.gauge("claudony.pool.max", tags, mgr, m -> m.status().max());
        registry.gauge("claudony.pool.fill_ratio", tags, mgr, m -> {
            var s = m.status();
            return s.max() == 0 ? 0.0 : (double) s.active() / s.max();
        });

        registry.gauge("claudony.pool.acquire_latency_avg_ms", tags, mgr,
            m -> m.lastDemandSnapshot().averageAcquireMs());
        registry.gauge("claudony.pool.acquire_latency_max_ms", tags, mgr,
            m -> m.lastDemandSnapshot().maxAcquireMs());

        var counters = new PoolCounters(
            Counter.builder("claudony.pool.acquires.total").tags(tags).register(registry),
            Counter.builder("claudony.pool.evictions.total").tags(tags).register(registry),
            Counter.builder("claudony.pool.exhaustions.total").tags(tags).register(registry)
        );
        countersByPool.put(poolName, counters);
    }

    public void recordAcquire(String poolName) {
        var c = countersByPool.get(poolName);
        if (c != null) c.acquires.increment();
    }

    public void recordEviction(String poolName) {
        var c = countersByPool.get(poolName);
        if (c != null) c.evictions.increment();
    }

    public void recordExhaustion(String poolName) {
        var c = countersByPool.get(poolName);
        if (c != null) c.exhaustions.increment();
    }

    private record PoolCounters(Counter acquires, Counter evictions, Counter exhaustions) {}

    private final Map<String, BudgetCounters> budgetCountersByPool = new ConcurrentHashMap<>();

    public void registerBudgetMetrics(String poolName) {
        var tags = Tags.of("pool", poolName);
        var budgetCounters = new BudgetCounters(
            Counter.builder("claudony.pool.budget.exceeded.total").tags(tags).register(registry),
            Counter.builder("claudony.pool.budget.reports.total").tags(tags).register(registry),
            Counter.builder("claudony.pool.budget.no_report_timeouts.total").tags(tags).register(registry)
        );
        budgetCountersByPool.put(poolName, budgetCounters);
    }

    public void recordCostReport(String poolName) {
        var c = budgetCountersByPool.get(poolName);
        if (c != null) c.reports.increment();
    }

    public void recordBudgetExceeded(String poolName) {
        var c = budgetCountersByPool.get(poolName);
        if (c != null) c.exceeded.increment();
    }

    public void recordNoReportTimeout(String poolName) {
        var c = budgetCountersByPool.get(poolName);
        if (c != null) c.noReportTimeouts.increment();
    }

    private record BudgetCounters(Counter exceeded, Counter reports, Counter noReportTimeouts) {}
}
