package dev.claudony.server.model;

import java.time.Instant;

public record SessionResponse(
        String id,
        String name,
        String workingDir,
        String command,
        SessionStatus status,
        Instant createdAt,
        Instant lastActive,
        String wsUrl,
        String browserUrl) {

    public static SessionResponse from(Session session, int port) {
        return new SessionResponse(
                session.id(), session.name(), session.workingDir(), session.command(),
                session.status(), session.createdAt(), session.lastActive(),
                "ws://localhost:" + port + "/ws/" + session.id(),
                "http://localhost:" + port + "/app/session/" + session.id());
    }
}
