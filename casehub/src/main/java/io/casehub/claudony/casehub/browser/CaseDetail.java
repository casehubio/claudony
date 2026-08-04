package io.casehub.claudony.casehub.browser;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CaseDetail(
    UUID id,
    String status,
    String definitionName,
    List<WorkerInfo> workers,
    List<String> channels,
    List<Map<String, Object>> timeline,
    Instant lastActivity
) {}
