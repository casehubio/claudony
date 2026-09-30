package io.casehub.claudony.casehub.fleet;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;

public class IoTDBFlatLabelAdapter {

    @FunctionalInterface
    public interface SqlWriter {
        void execute(String sql);
    }

    private final MeterRegistry registry;
    private final SqlWriter writer;

    public IoTDBFlatLabelAdapter(MeterRegistry registry, SqlWriter writer) {
        this.registry = registry;
        this.writer = writer;
    }

    public void tick() {
        List<String> pools = registry.getMeters().stream()
            .filter(m -> m.getId().getName().startsWith("claudony.pool."))
            .filter(m -> m.getId().getTag("pool") != null)
            .map(m -> m.getId().getTag("pool"))
            .distinct()
            .toList();

        for (var pool : pools) {
            double active = gaugeValue("claudony.pool.active", pool);
            double idle = gaugeValue("claudony.pool.idle", pool);
            double maxActive = gaugeValue("claudony.pool.max", pool);
            double fillRatio = gaugeValue("claudony.pool.fill_ratio", pool);
            double acquires = counterValue("claudony.pool.acquires.total", pool);
            double evictions = counterValue("claudony.pool.evictions.total", pool);
            double exhaustions = counterValue("claudony.pool.exhaustions.total", pool);

            var sql = String.format(
                "INSERT INTO pool_metrics(pool, active, idle, max_active, fill_ratio, acquires, evictions, exhaustions) " +
                "VALUES('%s', %d, %d, %d, %.4f, %d, %d, %d)",
                pool, (int) active, (int) idle, (int) maxActive, fillRatio,
                (long) acquires, (long) evictions, (long) exhaustions);
            writer.execute(sql);
        }
    }

    private double gaugeValue(String name, String pool) {
        var gauge = registry.find(name).tag("pool", pool).gauge();
        return gauge != null ? gauge.value() : 0.0;
    }

    private double counterValue(String name, String pool) {
        var counter = registry.find(name).tag("pool", pool).counter();
        return counter != null ? counter.count() : 0.0;
    }
}
