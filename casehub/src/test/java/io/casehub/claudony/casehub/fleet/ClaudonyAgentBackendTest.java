package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.claudony.config.ClaudonyConfig;
import io.casehub.claudony.server.TmuxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudonyAgentBackendTest {

    private TmuxService tmux;
    private ClaudonyConfig config;
    private ClaudonyAgentBackend backend;

    @BeforeEach
    void setUp() {
        tmux = mock(TmuxService.class);
        config = mock(ClaudonyConfig.class);
        when(config.defaultWorkingDir()).thenReturn("/tmp/claudony-workspace");
        backend = new ClaudonyAgentBackend(tmux, config);
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
}
