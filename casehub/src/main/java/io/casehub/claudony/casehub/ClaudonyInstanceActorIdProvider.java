package io.casehub.claudony.casehub;

import io.casehub.qhorus.api.spi.InstanceActorIdProvider;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

/**
 * Maps Claudony worker tmux session names to human-readable actor IDs for Qhorus ledger entries.
 *
 * <p>Qhorus uses {@code message.sender} (the tmux session name, e.g. {@code claudony-worker-{uuid}})
 * as the instanceId. This provider strips the prefix, looks up the role name, and returns
 * {@code claude:{roleName}@v1}. Falls back to the raw instanceId for unknown or terminated sessions.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class ClaudonyInstanceActorIdProvider implements InstanceActorIdProvider {

    static final String SESSION_PREFIX = "claudony-worker-";

    @Inject
    WorkerSessionMapping workerSessionMapping;

    @Override
    public String resolve(final String instanceId) {
        if (!instanceId.startsWith(SESSION_PREFIX)) {
            return instanceId;
        }
        String sessionUuid = instanceId.substring(SESSION_PREFIX.length());
        return workerSessionMapping.findBySessionId(sessionUuid)
                .map(roleName -> "claude:" + roleName + "@v1")
                .orElse(instanceId);
    }
}
