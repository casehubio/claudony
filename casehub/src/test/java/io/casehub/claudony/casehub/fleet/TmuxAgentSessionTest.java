package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.claudony.server.TmuxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TmuxAgentSessionTest {

    private TmuxService tmux;
    private AgentSessionManager sessionManager;
    private SessionOperations ops;
    private ManagedSession managedSession;
    private TmuxAgentSession session;

    @BeforeEach
    void setUp() {
        tmux = mock(TmuxService.class);
        ops = mock(SessionOperations.class);
        sessionManager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), ops);

        when(ops.create(any(), any())).thenReturn("pool-session-1");
        when(ops.conversationId(any())).thenReturn(null);

        managedSession = sessionManager.acquireSession("reviewer", "/workspace/pr-42");
        session = new TmuxAgentSession(managedSession, sessionManager, ops, tmux);
    }

    @Test
    void implementsAgentSession() {
        assertThat(session).isInstanceOf(AgentSession.class);
    }

    @Test
    void close_recordsInteractionMetrics() {
        when(ops.memoryBytes("pool-session-1")).thenReturn(100L * 1024 * 1024);

        session.close();

        verify(ops).memoryBytes("pool-session-1");
        assertThat(managedSession.lastMemoryBytes()).isEqualTo(100L * 1024 * 1024);
    }

    @Test
    void close_leavesSessionInManager() {
        when(ops.memoryBytes(any())).thenReturn(0L);

        session.close();

        assertThat(sessionManager.getSession("pool-session-1")).isNotNull();
        assertThat(sessionManager.getSession("pool-session-1").state())
                .isEqualTo(SessionState.ACTIVE);
    }

    @Test
    void close_isIdempotent() {
        when(ops.memoryBytes(any())).thenReturn(50L * 1024 * 1024);

        session.close();
        session.close();

        verify(ops, times(1)).memoryBytes(any());
    }

    @Test
    void close_withDuration_delegatesToClose() {
        when(ops.memoryBytes(any())).thenReturn(0L);

        session.close(Duration.ofSeconds(5));

        verify(ops).memoryBytes("pool-session-1");
    }

    @Test
    void query_afterClose_throwsIllegalState() {
        when(ops.memoryBytes(any())).thenReturn(0L);
        session.close();

        assertThatThrownBy(() -> session.query("test prompt"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void query_sendsPromptViaTmuxSendKeys() throws Exception {
        var events = session.query("Hello, Claude").collect().asList()
                .await().atMost(Duration.ofSeconds(5));

        verify(tmux).sendKeys("pool-session-1", "Hello, Claude\n");
        assertThat(events).isNotEmpty();
        assertThat(events.getLast()).isInstanceOf(AgentEvent.InvocationComplete.class);
    }

    @Test
    void interrupt_sendsCancelToTmux() throws Exception {
        session.interrupt().await().atMost(Duration.ofSeconds(5));

        verify(tmux).sendRawKeys("pool-session-1", "C-c");
    }

    @Test
    void interrupt_afterClose_isNoOp() throws Exception {
        when(ops.memoryBytes(any())).thenReturn(0L);
        session.close();

        session.interrupt().await().atMost(Duration.ofSeconds(5));

        verify(tmux, never()).sendRawKeys(any(), eq("C-c"));
    }

    @Test
    void managedSession_returnsWrappedSession() {
        assertThat(session.managedSession()).isSameAs(managedSession);
    }
}
