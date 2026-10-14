package io.casehub.claudony.server.model;

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
        Optional<String> expiryPolicy,
        Optional<String> caseId,
        Optional<String> roleName,
        String tenancyId,
        Optional<String> eidosAgentId) {

    public Session(String id, String name, String workingDir, String command,
                   SessionStatus status, Instant createdAt, Instant lastActive,
                   Optional<String> expiryPolicy, Optional<String> caseId,
                   Optional<String> roleName, String tenancyId) {
        this(id, name, workingDir, command, status, createdAt, lastActive,
             expiryPolicy, caseId, roleName, tenancyId, Optional.empty());
    }

    public Session withStatus(SessionStatus newStatus) {
        return new Session(id, name, workingDir, command, newStatus, createdAt, Instant.now(),
                           expiryPolicy, caseId, roleName, tenancyId, eidosAgentId);
    }

    public Session withLastActive() {
        return new Session(id, name, workingDir, command, status, createdAt, Instant.now(),
                           expiryPolicy, caseId, roleName, tenancyId, eidosAgentId);
    }
}
