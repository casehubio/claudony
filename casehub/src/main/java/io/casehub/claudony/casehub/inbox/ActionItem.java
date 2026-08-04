package io.casehub.claudony.casehub.inbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ActionItem(
    String id,
    SourceType sourceType,
    Urgency urgency,
    String title,
    String status,
    boolean actionable,
    UUID caseId,
    String channelName,
    Instant createdAt,
    List<ActionDescriptor> actions
) {}
