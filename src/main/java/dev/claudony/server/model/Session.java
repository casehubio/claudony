package dev.claudony.server.model;

import java.time.Instant;

public record Session(
        String id,
        String name,
        String workingDir,
        String command,
        SessionStatus status,
        Instant createdAt,
        Instant lastActive) {

    public Session withStatus(SessionStatus newStatus) {
        return new Session(id, name, workingDir, command, newStatus, createdAt, Instant.now());
    }

    public Session withLastActive() {
        return new Session(id, name, workingDir, command, status, createdAt, Instant.now());
    }
}
