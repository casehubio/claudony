package io.casehub.claudony.server.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionResponse(
        String id,
        String name,
        String workingDir,
        String command,
        SessionStatus status,
        Instant createdAt,
        Instant lastActive,
        String wsUrl,
        String browserUrl,
        String instanceUrl,
        String instanceName,
        Boolean stale,
        String expiryPolicy,
        String caseId,
        String roleName,
        String eidosAgentId) {

    public static SessionResponse from(Session session, int port, String effectivePolicy) {
        return new SessionResponse(
                session.id(), session.name(), session.workingDir(), session.command(),
                session.status(), session.createdAt(), session.lastActive(),
                "ws://localhost:" + port + "/ws/" + session.id(),
                "http://localhost:" + port + "/app/session/" + session.id(),
                null, null, null, effectivePolicy,
                session.caseId().orElse(null),
                session.roleName().orElse(null),
                session.eidosAgentId().orElse(null));
    }

    public SessionResponse withInstance(String peerUrl, String peerName, boolean isStale) {
        return new SessionResponse(
                id, name, workingDir, command, status, createdAt, lastActive,
                wsUrl, browserUrl, peerUrl, peerName, isStale, expiryPolicy,
                caseId, roleName, eidosAgentId);
    }
}
