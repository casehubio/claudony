package dev.claudony.server.model;

import java.time.Instant;
import java.util.Optional;

public record Session(
        String id,
        String name,
        String workingDir,
        String command,
        SessionStatus status,
        Instant createdAt,
        Instant lastActive,
        Optional<String> expiryPolicy) {  // internal only; never serialised — see SessionResponse for the API shape

    public Session withStatus(SessionStatus newStatus) {
        return new Session(id, name, workingDir, command, newStatus, createdAt, Instant.now(), expiryPolicy);
    }

    public Session withLastActive() {
        return new Session(id, name, workingDir, command, status, createdAt, Instant.now(), expiryPolicy);
    }
}
