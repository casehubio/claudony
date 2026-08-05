package io.casehub.claudony.casehub.inbox;

import java.time.Instant;
import java.util.UUID;

public record WorkItemView(
    UUID id,
    String title,
    String status,
    String priority,
    Instant expiresAt,
    Instant claimDeadline,
    Instant createdAt,
    String assigneeId,
    String actionBaseUrl
) {}
