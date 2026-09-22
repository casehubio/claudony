package io.casehub.claudony.casehub.fleet;

import io.casehub.claudony.server.TmuxService;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;

public class TmuxAgentSession implements AgentSession {

    private static final Logger LOG = Logger.getLogger(TmuxAgentSession.class);

    private final ManagedSession managedSession;
    private final AgentSessionManager sessionManager;
    private final SessionOperations ops;
    private final TmuxService tmux;
    private volatile boolean closed;

    public TmuxAgentSession(ManagedSession managedSession,
                            AgentSessionManager sessionManager,
                            SessionOperations ops,
                            TmuxService tmux) {
        this.managedSession = managedSession;
        this.sessionManager = sessionManager;
        this.ops = ops;
        this.tmux = tmux;
    }

    @Override
    public Multi<AgentEvent> query(String prompt) {
        if (closed) {
            throw new IllegalStateException("Session is closed");
        }
        return Multi.createFrom().emitter(em -> {
            try {
                tmux.sendKeys(managedSession.instanceId(), prompt + "\n");
                em.emit(new AgentEvent.InvocationComplete(
                        0, 0, 0, 0, 0, null, 0, 0,
                        managedSession.instanceId(), 1, false));
                em.complete();
            } catch (IOException | InterruptedException e) {
                em.fail(e);
            }
        });
    }

    @Override
    public Uni<Void> interrupt() {
        if (closed) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().item(() -> {
            try {
                tmux.sendRawKeys(managedSession.instanceId(), "C-c");
            } catch (IOException | InterruptedException e) {
                LOG.debugf("Interrupt failed for session %s: %s",
                        managedSession.instanceId(), e.getMessage());
            }
            return null;
        });
    }

    @Override
    public void close(Duration maxWait) {
        if (closed) return;
        closed = true;
        try {
            long memBytes = ops.memoryBytes(managedSession.instanceId());
            sessionManager.recordInteraction(managedSession.instanceId(), memBytes);
        } catch (Exception e) {
            LOG.debugf("Failed to record interaction on close for %s: %s",
                    managedSession.instanceId(), e.getMessage());
        }
        sessionManager.suspendSession(managedSession.instanceId());
    }

    public ManagedSession managedSession() {
        return managedSession;
    }
}
