package io.casehub.claudony.server.work;

import java.time.Instant;
import java.util.UUID;

public record WorkServiceResponse(
    UUID id,
    String title,
    String status,
    String priority,
    String assigneeId,
    Instant expiresAt,
    Instant claimDeadline,
    Instant createdAt
) {}
