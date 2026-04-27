package dev.claudony.casehub;

import dev.claudony.server.SessionRegistry;
import dev.claudony.server.TmuxService;
import dev.claudony.server.model.SessionStatus;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.WorkStatus;
import io.casehub.api.spi.WorkerStatusListener;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ClaudonyWorkerStatusListener implements WorkerStatusListener {

    private static final Logger LOG = Logger.getLogger(ClaudonyWorkerStatusListener.class);
    static final String SESSION_PREFIX = "claudony-worker-";

    private final SessionRegistry registry;
    private final TmuxService tmux;
    private final Event<Object> events;
    private final WorkerSessionMapping sessionMapping;

    @Inject
    public ClaudonyWorkerStatusListener(SessionRegistry registry, TmuxService tmux,
                                         Event<Object> events, WorkerSessionMapping sessionMapping) {
        this.registry = registry;
        this.tmux = tmux;
        this.events = events;
        this.sessionMapping = sessionMapping;
    }

    @Override
    public void onWorkerStarted(String roleName, Map<String, String> sessionMeta) {
        String caseId = sessionMeta != null ? sessionMeta.get("caseId") : null;
        String sessionId = caseId != null
                ? sessionMapping.findByCase(caseId, roleName)
                        .orElseGet(() -> sessionMapping.findByRole(roleName).orElse(null))
                : sessionMapping.findByRole(roleName).orElse(null);
        if (sessionId != null) {
            registry.updateStatus(sessionId, SessionStatus.ACTIVE);
        }
        LOG.debugf("Worker started: role=%s sessionId=%s", roleName, sessionId);
    }

    @Override
    public void onWorkerCompleted(String roleName, WorkResult result) {
        String sessionId = sessionMapping.findByRole(roleName).orElse(null);
        if (sessionId == null) {
            LOG.warnf("No session found for worker role: %s", roleName);
            return;
        }
        if (result.status() == WorkStatus.FAULTED) {
            try {
                tmux.killSession(SESSION_PREFIX + sessionId);
            } catch (IOException | InterruptedException e) {
                LOG.warnf("Could not kill faulted session for worker %s (session %s): %s",
                        roleName, sessionId, e.getMessage());
            }
            registry.remove(sessionId);
            sessionMapping.remove(roleName);
        } else {
            registry.find(sessionId).ifPresent(session ->
                    registry.updateStatus(sessionId, SessionStatus.IDLE));
        }
        LOG.debugf("Worker completed: role=%s sessionId=%s status=%s", roleName, sessionId, result.status());
    }

    @Override
    public void onWorkerStalled(String workerId) {
        LOG.warnf("Worker stalled: %s", workerId);
        events.fire(new WorkerStalledEvent(workerId));
    }

    public record WorkerStalledEvent(String workerId) {}
}
