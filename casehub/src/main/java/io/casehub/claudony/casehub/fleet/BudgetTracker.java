package io.casehub.claudony.casehub.fleet;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@jakarta.enterprise.context.ApplicationScoped
public class BudgetTracker {

    private final ConcurrentHashMap<String, PoolBudgetState> states = new ConcurrentHashMap<>();

    public void record(String poolName, CostReport report) {
        var state = states.computeIfAbsent(poolName, k -> new PoolBudgetState());
        state.record(poolName, report);
    }

    public BudgetCheckResult checkBudget(String poolName, BudgetConfig config) {
        var state = states.get(poolName);
        if (state == null) {
            return new BudgetCheckResult(false, null, 0.0,
                    config.costLimit() != null ? config.costLimit() : 0.0,
                    0, config.tokenLimit() != null ? config.tokenLimit() : 0,
                    config.window());
        }
        return state.checkBudget(config);
    }

    public List<CostEntry> flush(String poolName) {
        var state = states.get(poolName);
        if (state == null) return List.of();
        return state.flush();
    }

    public void invalidate(String poolName) {
        states.remove(poolName);
    }

    public boolean isBudgetExceeded(String poolName) {
        var state = states.get(poolName);
        return state != null && state.budgetExceeded;
    }

    public void setBudgetExceeded(String poolName, boolean exceeded) {
        states.computeIfAbsent(poolName, k -> new PoolBudgetState()).budgetExceeded = exceeded;
    }

    public Optional<Instant> lastReportTime(String poolName, String sessionId) {
        var state = states.get(poolName);
        if (state == null) return Optional.empty();
        var snapshot = state.sessionSnapshots.get(sessionId);
        if (snapshot == null) return Optional.empty();
        return Optional.of(snapshot.lastReportTime);
    }

    public void bootstrap(Map<String, List<CostEntry>> persistedEntries) {
        for (var entry : persistedEntries.entrySet()) {
            var state = states.computeIfAbsent(entry.getKey(), k -> new PoolBudgetState());
            state.entries.addAll(entry.getValue());
        }
    }

    static final class PoolBudgetState {
        final List<CostEntry> entries = Collections.synchronizedList(new ArrayList<>());
        final List<CostEntry> unflushed = Collections.synchronizedList(new ArrayList<>());
        final ConcurrentHashMap<String, SessionCostSnapshot> sessionSnapshots = new ConcurrentHashMap<>();
        volatile boolean budgetExceeded;

        void record(String poolName, CostReport report) {
            var snapshot = sessionSnapshots.get(report.sessionId());
            double deltaCost;
            long deltaTokens;
            long totalTokens = report.inputTokens() + report.outputTokens();

            if (snapshot != null) {
                deltaCost = report.totalCostUsd() - snapshot.lastCostUsd;
                deltaTokens = totalTokens - snapshot.lastTokens;
            } else {
                deltaCost = report.totalCostUsd();
                deltaTokens = totalTokens;
            }

            sessionSnapshots.put(report.sessionId(),
                    new SessionCostSnapshot(report.totalCostUsd(), totalTokens, report.timestamp()));

            var costEntry = new CostEntry(poolName, report.sessionId(),
                    deltaCost, deltaTokens, report.model(), report.timestamp());
            entries.add(costEntry);
            unflushed.add(costEntry);
        }

        BudgetCheckResult checkBudget(BudgetConfig config) {
            var now = Instant.now();
            var windowStart = now.minus(config.window());

            double totalCost = 0;
            long totalTokens = 0;

            synchronized (entries) {
                entries.removeIf(e -> e.timestamp().isBefore(windowStart));
                for (var entry : entries) {
                    totalCost += entry.deltaCostUsd();
                    totalTokens += entry.deltaTokens();
                }
            }

            double costLimit = config.costLimit() != null ? config.costLimit() : Double.MAX_VALUE;
            long tokenLimit = config.tokenLimit() != null ? config.tokenLimit() : Long.MAX_VALUE;
            var windowRemaining = Duration.between(now, windowStart.plus(config.window()));

            if (config.costLimit() != null && totalCost > costLimit) {
                return new BudgetCheckResult(true, BudgetDimension.COST,
                        totalCost, costLimit, totalTokens, tokenLimit, windowRemaining);
            }
            if (config.tokenLimit() != null && totalTokens > tokenLimit) {
                return new BudgetCheckResult(true, BudgetDimension.TOKENS,
                        totalCost, costLimit, totalTokens, tokenLimit, windowRemaining);
            }

            return new BudgetCheckResult(false, null,
                    totalCost, costLimit, totalTokens, tokenLimit, windowRemaining);
        }

        List<CostEntry> flush() {
            synchronized (unflushed) {
                var result = new ArrayList<>(unflushed);
                unflushed.clear();
                return result;
            }
        }
    }

    record SessionCostSnapshot(double lastCostUsd, long lastTokens, Instant lastReportTime) {}
}
