package io.casehub.claudony.casehub.fleet;

import java.time.Duration;

public record BudgetConfig(
        Double costLimit,
        Long tokenLimit,
        Duration window,
        EnforcementPolicy enforcement,
        ReportInterval reportInterval,
        Duration noReportTimeout
) {}
