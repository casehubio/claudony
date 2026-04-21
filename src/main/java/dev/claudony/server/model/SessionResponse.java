package dev.claudony.server.model;

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
        String instanceUrl,   // null for local sessions; peer URL for remote sessions
        String instanceName,  // null for local sessions; peer name for remote sessions
        Boolean stale,        // null for local; true if from stale cache, false if live
        String expiryPolicy) { // null if unknown (e.g. peer running older binary)

    /** Local session — fleet fields absent (NON_NULL serialization omits nulls). */
    public static SessionResponse from(Session session, int port, String effectivePolicy) {
        return new SessionResponse(
                session.id(), session.name(), session.workingDir(), session.command(),
                session.status(), session.createdAt(), session.lastActive(),
                "ws://localhost:" + port + "/ws/" + session.id(),
                "http://localhost:" + port + "/app/session/" + session.id(),
                null, null, null, effectivePolicy);
    }

    /** Remote session from a peer — preserves the wsUrl/browserUrl pointing to the peer instance. */
    public SessionResponse withInstance(String peerUrl, String peerName, boolean isStale) {
        return new SessionResponse(
                id, name, workingDir, command, status, createdAt, lastActive,
                wsUrl, browserUrl, peerUrl, peerName, isStale, expiryPolicy);
    }
}
