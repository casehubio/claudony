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

    private final TmuxService         tmux;
    private final SessionRegistry     registry;
    private final AgentSessionManager sessionManager;

    @Inject
    public ClaudonyAgentBackend(TmuxService tmux, SessionRegistry registry) {
        this.tmux           = tmux;
        this.registry       = registry;
        this.sessionManager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 10),
                new SessionOperations() {
                    @Override
                    public String create(String identity, String workingDir) {
                        throw new UnsupportedOperationException("Session factory not yet wired");
                    }

                    @Override
                    public String conversationId(String sessionId) {
                        return null;
                    }

                    @Override
                    public void suspend(String sessionId) {}

                    @Override
                    public void resume(String sessionId, String conversationId, String workingDir) {}

                    @Override
                    public void destroy(String sessionId) {}

                    @Override
                    public long memoryBytes(String sessionId) {
                        return 0;
                    }
                }
        );
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
