package io.casehub.claudony.casehub.fleet;

import io.casehub.claudony.server.TmuxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TmuxSessionOperationsTest {

    private TmuxService tmux;
    private TmuxSessionOperations ops;

    @BeforeEach
    void setUp() {
        tmux = mock(TmuxService.class);
        ops = new TmuxSessionOperations(tmux, "claudony-pool-", "claude");
    }

    @Test
    void create_callsCreateWorkerSession() throws Exception {
        String sessionId = ops.create("reviewer", "/workspace/pr-42");
        assertThat(sessionId).startsWith("claudony-pool-");
        verify(tmux).createWorkerSession(eq(sessionId), eq("/workspace/pr-42"), eq("claude"));
    }

    @Test
    void create_storesIdentityAsSessionOption() throws Exception {
        String sessionId = ops.create("reviewer", "/workspace/pr-42");
        verify(tmux).setSessionOption(sessionId, "@claudony_identity", "reviewer");
    }

    @Test
    void suspend_killsSession() throws Exception {
        ops.suspend("claudony-pool-abc123");
        verify(tmux).killSession("claudony-pool-abc123");
    }

    @Test
    void suspend_toleratesAlreadyGone() throws Exception {
        doThrow(new IOException("session not found")).when(tmux).killSession(any());
        assertThatCode(() -> ops.suspend("claudony-pool-gone")).doesNotThrowAnyException();
    }

    @Test
    void resume_createsSessionWithContinueFlag() throws Exception {
        ops.resume("claudony-pool-abc123", "conv_xyz", "/workspace/pr-42");
        verify(tmux).createWorkerSession("claudony-pool-abc123", "/workspace/pr-42", "claude -c conv_xyz");
    }

    @Test
    void destroy_killsSession() throws Exception {
        ops.destroy("claudony-pool-abc123");
        verify(tmux).killSession("claudony-pool-abc123");
    }

    @Test
    void destroy_toleratesAlreadyGone() throws Exception {
        doThrow(new IOException("session not found")).when(tmux).killSession(any());
        assertThatCode(() -> ops.destroy("claudony-pool-gone")).doesNotThrowAnyException();
    }

    @Test
    void memoryBytes_readsPidAndRss() throws Exception {
        when(tmux.displayMessage("claudony-pool-abc123", "#{pane_pid}")).thenReturn("12345");
        var result = ops.memoryBytes("claudony-pool-abc123");
        assertThat(result).isGreaterThanOrEqualTo(0);
    }

    @Test
    void memoryBytes_returnsZeroWhenSessionGone() throws Exception {
        when(tmux.displayMessage(any(), any())).thenThrow(new IOException("session gone"));
        assertThat(ops.memoryBytes("claudony-pool-gone")).isZero();
    }

    @Test
    void conversationId_returnsNullForNewSession() {
        assertThat(ops.conversationId("claudony-pool-new")).isNull();
    }
}
