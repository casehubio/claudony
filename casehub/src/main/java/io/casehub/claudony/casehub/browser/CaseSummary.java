package io.casehub.claudony.casehub.browser;

import java.time.Instant;
import java.util.UUID;

public record CaseSummary(
    UUID id,
    String status,
    String definitionName,
    int activeWorkerCount,
    int channelCount,
    Instant lastActivity
) {}
