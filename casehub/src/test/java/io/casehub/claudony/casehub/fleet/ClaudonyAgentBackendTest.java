package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.SessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudonyAgentBackendTest {

    private TmuxService tmux;
    private SessionRegistry registry;
    private ClaudonyAgentBackend backend;

    @BeforeEach
    void setUp() {
        tmux = mock(TmuxService.class);
        registry = mock(SessionRegistry.class);
        backend = new ClaudonyAgentBackend(tmux, registry);
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
    void openSession_throwsUnsupported() {
        var init = AgentSessionInit.of("system");
        assertThatThrownBy(() -> backend.openSession(init))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
