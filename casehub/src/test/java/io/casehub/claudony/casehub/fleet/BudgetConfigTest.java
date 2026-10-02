package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetConfigTest {

    @Test
    void fullConfig() {
        var config = new BudgetConfig(50.0, 10_000_000L, Duration.ofHours(24),
                EnforcementPolicy.SUSPEND, new ReportInterval.Turn(), Duration.ofMinutes(10));
        assertThat(config.costLimit()).isEqualTo(50.0);
        assertThat(config.tokenLimit()).isEqualTo(10_000_000L);
        assertThat(config.window()).isEqualTo(Duration.ofHours(24));
        assertThat(config.enforcement()).isEqualTo(EnforcementPolicy.SUSPEND);
        assertThat(config.reportInterval()).isInstanceOf(ReportInterval.Turn.class);
        assertThat(config.noReportTimeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void nullLimitsAllowed() {
        var config = new BudgetConfig(null, null, Duration.ofHours(1),
                EnforcementPolicy.ALERT, new ReportInterval.Completion(), Duration.ofMinutes(5));
        assertThat(config.costLimit()).isNull();
        assertThat(config.tokenLimit()).isNull();
    }

    @Test
    void periodicInterval() {
        var interval = new ReportInterval.Periodic(5);
        assertThat(interval.turns()).isEqualTo(5);
    }

    @Test
    void enforcementValues() {
        assertThat(EnforcementPolicy.values()).containsExactly(
                EnforcementPolicy.SUSPEND, EnforcementPolicy.BLOCK_NEW, EnforcementPolicy.ALERT);
    }
}
