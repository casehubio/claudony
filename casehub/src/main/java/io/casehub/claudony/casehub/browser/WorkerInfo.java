package io.casehub.claudony.casehub.browser;

import java.time.Instant;

public record WorkerInfo(
    String sessionId,
    String roleName,
    String status,
    Instant lastActive,
    boolean active
) {}
