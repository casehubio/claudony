package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetTrackerTest {

    private BudgetTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new BudgetTracker();
    }

    @Test
    void recordComputesDeltaFromCumulativeReports() {
        var config = budgetConfig(100.0, null);
        var report1 = costReport("s1", 5.0, 1000, 500);
        tracker.record("pool-a", report1);
        var report2 = costReport("s1", 12.0, 2500, 1200);
        tracker.record("pool-a", report2);
        var result = tracker.checkBudget("pool-a", config);
        assertThat(result.currentCostUsd()).isEqualTo(12.0);
        assertThat(result.currentTokens()).isEqualTo(2500 + 1200);
        assertThat(result.overBudget()).isFalse();
    }

    @Test
    void windowExpiry() {
        var config = budgetConfig(100.0, null, Duration.ofMinutes(5));
        var oldReport = new CostReport("s1", 50.0, 5000, 2000, 0, 0, "opus",
                Instant.now().minus(Duration.ofMinutes(10)));
        tracker.record("pool-a", oldReport);
        var result = tracker.checkBudget("pool-a", config);
        assertThat(result.currentCostUsd()).isEqualTo(0.0);
    }

    @Test
    void costLimitExceeded() {
        var config = budgetConfig(10.0, null);
        tracker.record("pool-a", costReport("s1", 11.0, 5000, 2000));
        var result = tracker.checkBudget("pool-a", config);
        assertThat(result.overBudget()).isTrue();
        assertThat(result.violatedDimension()).isEqualTo(BudgetDimension.COST);
    }

    @Test
    void tokenLimitExceeded() {
        var config = budgetConfig(null, 5000L);
        tracker.record("pool-a", costReport("s1", 1.0, 3000, 3000));
        var result = tracker.checkBudget("pool-a", config);
        assertThat(result.overBudget()).isTrue();
        assertThat(result.violatedDimension()).isEqualTo(BudgetDimension.TOKENS);
    }

    @Test
    void costCheckedBeforeTokens() {
        var config = new BudgetConfig(10.0, 5000L, Duration.ofHours(1),
                EnforcementPolicy.ALERT, new ReportInterval.Turn(), Duration.ofMinutes(10));
        tracker.record("pool-a", costReport("s1", 11.0, 3000, 3000));
        var result = tracker.checkBudget("pool-a", config);
        assertThat(result.overBudget()).isTrue();
        assertThat(result.violatedDimension()).isEqualTo(BudgetDimension.COST);
    }

    @Test
    void nullLimitsNeverExceeded() {
        var config = budgetConfig(null, null);
        tracker.record("pool-a", costReport("s1", 1000.0, 999999, 999999));
        var result = tracker.checkBudget("pool-a", config);
        assertThat(result.overBudget()).isFalse();
        assertThat(result.violatedDimension()).isNull();
    }

    @Test
    void flushReturnsUnflushedEntries() {
        tracker.record("pool-a", costReport("s1", 5.0, 1000, 500));
        tracker.record("pool-a", costReport("s1", 10.0, 2000, 1000));
        List<CostEntry> flushed = tracker.flush("pool-a");
        assertThat(flushed).hasSize(2);
        assertThat(flushed.get(0).deltaCostUsd()).isEqualTo(5.0);
        assertThat(flushed.get(1).deltaCostUsd()).isEqualTo(5.0);
        List<CostEntry> secondFlush = tracker.flush("pool-a");
        assertThat(secondFlush).isEmpty();
    }

    @Test
    void flushUnknownPoolReturnsEmpty() {
        assertThat(tracker.flush("nonexistent")).isEmpty();
    }

    @Test
    void invalidateClearsStateAndFlag() {
        tracker.record("pool-a", costReport("s1", 50.0, 5000, 2000));
        tracker.setBudgetExceeded("pool-a", true);
        tracker.invalidate("pool-a");
        assertThat(tracker.isBudgetExceeded("pool-a")).isFalse();
        var result = tracker.checkBudget("pool-a", budgetConfig(100.0, null));
        assertThat(result.currentCostUsd()).isEqualTo(0.0);
    }

    @Test
    void budgetExceededFlag() {
        assertThat(tracker.isBudgetExceeded("pool-a")).isFalse();
        tracker.setBudgetExceeded("pool-a", true);
        assertThat(tracker.isBudgetExceeded("pool-a")).isTrue();
        tracker.setBudgetExceeded("pool-a", false);
        assertThat(tracker.isBudgetExceeded("pool-a")).isFalse();
    }

    @Test
    void lastReportTime() {
        var now = Instant.now();
        tracker.record("pool-a", new CostReport("s1", 5.0, 1000, 500, 0, 0, "opus", now));
        assertThat(tracker.lastReportTime("pool-a", "s1")).contains(now);
        assertThat(tracker.lastReportTime("pool-a", "unknown")).isEmpty();
    }

    @Test
    void lastReportTimeUnknownPool() {
        assertThat(tracker.lastReportTime("nonexistent", "s1")).isEmpty();
    }

    @Test
    void bootstrapLoadsEntries() {
        var entry = new CostEntry("pool-a", "s1", 5.0, 1500, "opus", Instant.now());
        tracker.bootstrap(Map.of("pool-a", List.of(entry)));
        var result = tracker.checkBudget("pool-a", budgetConfig(100.0, null));
        assertThat(result.currentCostUsd()).isEqualTo(5.0);
    }

    @Test
    void multipleSessions() {
        var config = budgetConfig(20.0, null);
        tracker.record("pool-a", costReport("s1", 8.0, 1000, 500));
        tracker.record("pool-a", costReport("s2", 7.0, 1000, 500));
        var result = tracker.checkBudget("pool-a", config);
        assertThat(result.currentCostUsd()).isEqualTo(15.0);
        assertThat(result.overBudget()).isFalse();
    }

    @Test
    void checkBudgetUnknownPoolReturnsUnderBudget() {
        var result = tracker.checkBudget("nonexistent", budgetConfig(100.0, null));
        assertThat(result.overBudget()).isFalse();
        assertThat(result.currentCostUsd()).isEqualTo(0.0);
    }

    private static BudgetConfig budgetConfig(Double costLimit, Long tokenLimit) {
        return budgetConfig(costLimit, tokenLimit, Duration.ofHours(1));
    }

    private static BudgetConfig budgetConfig(Double costLimit, Long tokenLimit, Duration window) {
        return new BudgetConfig(costLimit, tokenLimit, window,
                EnforcementPolicy.ALERT, new ReportInterval.Turn(), Duration.ofMinutes(10));
    }

    private static CostReport costReport(String sessionId, double costUsd, long input, long output) {
        return new CostReport(sessionId, costUsd, input, output, 0, 0, "opus", Instant.now());
    }
}
