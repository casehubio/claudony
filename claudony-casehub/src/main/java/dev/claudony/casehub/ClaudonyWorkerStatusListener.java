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

    @Inject
    public ClaudonyWorkerStatusListener(SessionRegistry registry, TmuxService tmux,
                                         Event<Object> events) {
        this.registry = registry;
        this.tmux = tmux;
        this.events = events;
    }

    @Override
    public void onWorkerStarted(String workerId, Map<String, String> sessionMeta) {
        registry.find(workerId).ifPresent(session ->
                registry.updateStatus(workerId, SessionStatus.ACTIVE));
        LOG.debugf("Worker started: %s", workerId);
    }

    @Override
    public void onWorkerCompleted(String workerId, WorkResult result) {
        if (result.status() == WorkStatus.FAULTED) {
            try {
                tmux.killSession(SESSION_PREFIX + workerId);
            } catch (IOException | InterruptedException e) {
                LOG.warnf("Could not kill faulted session for worker %s: %s", workerId, e.getMessage());
            }
            registry.remove(workerId);
        } else {
            registry.find(workerId).ifPresent(session ->
                    registry.updateStatus(workerId, SessionStatus.IDLE));
        }
        LOG.debugf("Worker completed: %s status=%s", workerId, result.status());
    }

    @Override
    public void onWorkerStalled(String workerId) {
        LOG.warnf("Worker stalled: %s", workerId);
        events.fire(new WorkerStalledEvent(workerId));
    }

    public record WorkerStalledEvent(String workerId) {}
}
