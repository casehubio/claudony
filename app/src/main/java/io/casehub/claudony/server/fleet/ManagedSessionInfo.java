package io.casehub.claudony.server.fleet;

import java.time.Instant;

public record ManagedSessionInfo(
    String instanceId, String identity, String workingDir,
    String conversationId, String state,
    Instant lastInteraction, long memoryBytes, long idleSeconds
) {}
