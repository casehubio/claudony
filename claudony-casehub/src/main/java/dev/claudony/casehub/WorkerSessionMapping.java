package dev.claudony.casehub;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges CaseHub worker role names to Claudony tmux session IDs.
 *
 * <p>CaseHub identifies workers by role name (e.g. "code-reviewer") while Claudony tracks
 * sessions by UUID. This mapping is the coupling point between the two identity systems.
 *
 * <p>Keyed by both caseId+role (precise) and role alone (fallback for callers that lack
 * caseId, such as onWorkerCompleted). Assumes at most one active instance per role —
 * concurrent same-role workers across cases would require caseId in WorkResult (tracked
 * separately as an upstream casehub-engine enhancement).
 */
@ApplicationScoped
public class WorkerSessionMapping {

    // precise key: "{caseId}:{roleName}" → sessionId
    private final ConcurrentHashMap<String, String> byCase = new ConcurrentHashMap<>();

    // fallback key: roleName → sessionId (last registered wins)
    private final ConcurrentHashMap<String, String> byRole = new ConcurrentHashMap<>();

    void register(final String roleName, final UUID caseId, final String sessionId) {
        byRole.put(roleName, sessionId);
        if (caseId != null) {
            byCase.put(caseId + ":" + roleName, sessionId);
        }
    }

    Optional<String> findByCase(final String caseId, final String roleName) {
        return Optional.ofNullable(byCase.get(caseId + ":" + roleName));
    }

    Optional<String> findByRole(final String roleName) {
        return Optional.ofNullable(byRole.get(roleName));
    }

    void remove(final String roleName) {
        byRole.remove(roleName);
        byCase.entrySet().removeIf(e -> e.getKey().endsWith(":" + roleName));
    }
}
