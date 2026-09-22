package io.casehub.claudony.casehub.fleet;

import io.casehub.claudony.config.ClaudonyConfig;
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
    private final SessionOperations ops;
    private final TmuxService tmux;
    private final ClaudonyConfig config;

    @Inject
    public ClaudonyAgentBackend(TmuxService tmux, ClaudonyConfig config) {
        this.tmux = tmux;
        this.config = config;
        this.ops = new TmuxSessionOperations(tmux, SESSION_PREFIX, "claude");
        this.sessionManager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 10),
                ops
        );
    }

    public ClaudonyAgentBackend(AgentSessionManager sessionManager, SessionOperations ops,
                                TmuxService tmux, ClaudonyConfig config) {
        this.sessionManager = sessionManager;
        this.ops = ops;
        this.tmux = tmux;
        this.config = config;
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
        String identity = init.correlationId() != null ? init.correlationId() : "default";
        String workingDir = config.defaultWorkingDir();
        ManagedSession managed = sessionManager.acquireSession(identity, workingDir);
        return new TmuxAgentSession(managed, sessionManager, ops, tmux);
    }

    public TmuxAgentSession openWorkerSession(String identity, String workingDir, String command) {
        ManagedSession managed = sessionManager.acquireSession(identity, workingDir,
                                                               command, WorkingDirPolicy.SHARED_READ);
        return new TmuxAgentSession(managed, sessionManager, ops, tmux);
    }

    public AgentPoolStatus poolStatus() {
        return sessionManager.status();
    }

    public AgentSessionManager sessionManager() {
        return sessionManager;
    }
}
