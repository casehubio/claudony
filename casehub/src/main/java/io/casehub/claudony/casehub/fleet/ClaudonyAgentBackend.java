package io.casehub.claudony.casehub.fleet;

import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ClaudonyAgentBackend implements AgentBackend {

    static final String SESSION_PREFIX = "claudony-pool-";

    private final AgentSessionManager sessionManager;

    @Inject
    public ClaudonyAgentBackend(TmuxService tmux, SessionRegistry registry) {
        var ops = new TmuxSessionOperations(tmux, SESSION_PREFIX, "claude");
        this.sessionManager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 10),
                ops
        );
    }

    ClaudonyAgentBackend(AgentSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public String key() {
        return "claudony";
    }

    @Override
    public String instanceId() {
        return "default";
    }

    @Override
    public Multi<AgentEvent> invoke(AgentSessionConfig config) {
        throw new UnsupportedOperationException("CLI invoke not yet implemented");
    }

    @Override
    public AgentSession openSession(AgentSessionInit init) {
        throw new UnsupportedOperationException("CLI openSession not yet implemented");
    }

    public AgentPoolStatus poolStatus() {
        return sessionManager.status();
    }

    public AgentSessionManager sessionManager() {
        return sessionManager;
    }
}
