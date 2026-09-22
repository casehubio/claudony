package io.casehub.claudony.casehub.fleet;

import io.casehub.claudony.config.ClaudonyConfig;
import io.casehub.claudony.server.TmuxService;
import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaudonyAgentBackendTest {

    private TmuxService tmux;
    private ClaudonyConfig config;
    private AgentPoolConfig poolConfig;

    private ClaudonyAgentBackend backend;

    @BeforeEach
    void setUp() {
        tmux       = mock(TmuxService.class);
        config     = mock(ClaudonyConfig.class);
        poolConfig = mock(AgentPoolConfig.class);
        when(config.defaultWorkingDir()).thenReturn("/tmp/claudony-workspace");
        when(poolConfig.minActive()).thenReturn(0);
        when(poolConfig.maxActive()).thenReturn(10);
        backend = new ClaudonyAgentBackend(tmux, config, poolConfig);
    }

    @Test
    void key_isClaudony() {
        assertThat(backend.key()).isEqualTo("claudony");
    }

    @Test
    void instanceId_isDefault() {
        assertThat(backend.instanceId()).isEqualTo("default");
    }

    @Test
    void implementsAgentBackend() {
        assertThat(backend).isInstanceOf(AgentBackend.class);
    }

    @Test
    void poolStatus_returnsStatus() {
        var status = backend.poolStatus();
        assertThat(status).isNotNull();
        assertThat(status.health()).isEqualTo(AgentPoolHealth.HEALTHY);
    }

    @Test
    void poolStatus_reflectsConfiguredMinMax() {
        var customPoolConfig = mock(AgentPoolConfig.class);
        when(customPoolConfig.minActive()).thenReturn(2);
        when(customPoolConfig.maxActive()).thenReturn(20);
        var customBackend = new ClaudonyAgentBackend(tmux, config, customPoolConfig);
        var status        = customBackend.poolStatus();
        assertThat(status.min()).isEqualTo(2);
        assertThat(status.max()).isEqualTo(20);
    }


    @Test
    void invoke_throwsUnsupported() {
        var config = AgentSessionConfig.of("system", "user");
        assertThatThrownBy(() -> backend.invoke(config))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void openSession_returnsAgentSession() {
        var init = AgentSessionInit.of("system prompt");
        AgentSession session = backend.openSession(init);
        assertThat(session).isNotNull();
        assertThat(session).isInstanceOf(TmuxAgentSession.class);
    }

    @Test
    void openSession_usesCorrelationIdAsIdentity() {
        var init = new AgentSessionInit("system", java.util.List.of(), null, "reviewer", null);
        AgentSession session = backend.openSession(init);
        var tmuxSession = (TmuxAgentSession) session;
        assertThat(tmuxSession.managedSession().identity()).isEqualTo("reviewer");
    }

    @Test
    void openSession_usesDefaultIdentityWhenNoCorrelationId() {
        var init = AgentSessionInit.of("system prompt");
        var tmuxSession = (TmuxAgentSession) backend.openSession(init);
        assertThat(tmuxSession.managedSession().identity()).isEqualTo("default");
    }

    @Test
    void openSession_usesConfiguredWorkingDir() {
        var init = AgentSessionInit.of("system prompt");
        var tmuxSession = (TmuxAgentSession) backend.openSession(init);
        assertThat(tmuxSession.managedSession().workingDir()).isEqualTo("/tmp/claudony-workspace");
    }

    @Test
    void openWorkerSession_passesIdentityAndWorkingDir() {
        var session = backend.openWorkerSession("reviewer", "/workspace/pr-42", "claude");
        assertThat(session.managedSession().identity()).isEqualTo("reviewer");
        assertThat(session.managedSession().workingDir()).isEqualTo("/workspace/pr-42");
    }

    @Test
    void openWorkerSession_usesSharedReadPolicy() {
        backend.openWorkerSession("reviewer", "/workspace/pr-42", "claude");
        var second = backend.openWorkerSession("coder", "/workspace/pr-42", "claude");
        assertThat(second).isNotNull();
        assertThat(backend.sessionManager().activeCount()).isEqualTo(2);
    }

    @Test
    void openWorkerSession_passesCommand() {
        var session = backend.openWorkerSession("reviewer", "/workspace/pr-42", "claude --model opus");
        assertThat(session).isNotNull();
        assertThat(session.managedSession()).isNotNull();
    }
}
