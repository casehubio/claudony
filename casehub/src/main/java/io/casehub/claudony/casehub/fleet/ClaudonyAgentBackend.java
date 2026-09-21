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

import java.time.Duration;

@ApplicationScoped
public class ClaudonyAgentBackend implements AgentBackend {

    private final TmuxService tmux;
    private final SessionRegistry registry;
    private final AgentPool pool;

    @Inject
    public ClaudonyAgentBackend(TmuxService tmux, SessionRegistry registry) {
        this.tmux = tmux;
        this.registry = registry;
        this.pool = new AgentPool(
                new AgentPoolConfig(0, 10, Duration.ofMinutes(15), Duration.ofSeconds(10)),
                () -> { throw new UnsupportedOperationException("Session factory not yet wired"); },
                sessionId -> {}
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
        return pool.status();
    }
}
