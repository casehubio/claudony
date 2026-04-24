package dev.claudony.casehub;

import dev.claudony.server.SessionRegistry;
import dev.claudony.server.TmuxService;
import dev.claudony.server.model.Session;
import dev.claudony.server.model.SessionStatus;
import io.casehub.api.model.WorkResult;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudonyWorkerStatusListenerTest {

    private SessionRegistry registry;
    private TmuxService tmux;
    private ClaudonyWorkerStatusListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = mock(SessionRegistry.class);
        tmux = mock(TmuxService.class);
        Event<Object> events = mock(Event.class);
        listener = new ClaudonyWorkerStatusListener(registry, tmux, events);
    }

    @Test
    void onWorkerStarted_updatesSessionToActive() {
        String workerId = "worker-123";
        when(registry.find(workerId)).thenReturn(Optional.of(session(workerId, SessionStatus.IDLE)));

        listener.onWorkerStarted(workerId, java.util.Map.of("session", "tmux-123"));

        verify(registry).updateStatus(workerId, SessionStatus.ACTIVE);
    }

    @Test
    void onWorkerStarted_sessionNotFound_isNoOp() {
        when(registry.find("unknown")).thenReturn(Optional.empty());

        assertThatNoException().isThrownBy(() ->
                listener.onWorkerStarted("unknown", java.util.Map.of()));
        verify(registry, never()).updateStatus(any(), any());
    }

    @Test
    void onWorkerCompleted_completedResult_updatesSessionToIdle() {
        String workerId = "worker-456";
        when(registry.find(workerId)).thenReturn(Optional.of(session(workerId, SessionStatus.ACTIVE)));
        var result = WorkResult.completed("corr-1", java.util.Map.of(), workerId);

        listener.onWorkerCompleted(workerId, result);

        verify(registry).updateStatus(workerId, SessionStatus.IDLE);
    }

    @Test
    void onWorkerCompleted_faultedResult_terminatesSession() throws Exception {
        String workerId = "worker-faulted";
        when(registry.find(workerId)).thenReturn(Optional.of(session(workerId, SessionStatus.ACTIVE)));
        var result = WorkResult.faulted("corr-1", workerId);

        listener.onWorkerCompleted(workerId, result);

        verify(tmux).killSession(ClaudonyWorkerStatusListener.SESSION_PREFIX + workerId);
        verify(registry).remove(workerId);
    }

    @Test
    void onWorkerCompleted_faultedAndTerminateFails_stillRemovesFromRegistry() throws Exception {
        String workerId = "worker-x";
        when(registry.find(workerId)).thenReturn(Optional.of(session(workerId, SessionStatus.ACTIVE)));
        doThrow(new java.io.IOException("tmux gone")).when(tmux).killSession(anyString());
        var result = WorkResult.faulted("corr-1", workerId);

        assertThatNoException().isThrownBy(() -> listener.onWorkerCompleted(workerId, result));
        verify(registry).remove(workerId);
    }

    @Test
    void onWorkerStalled_doesNotTerminateSession() throws Exception {
        listener.onWorkerStalled("worker-stalled");
        verifyNoInteractions(tmux);
    }

    private Session session(String id, SessionStatus status) {
        return new Session(id, ClaudonyWorkerStatusListener.SESSION_PREFIX + id, "/tmp", "claude",
                status, Instant.now(), Instant.now(), Optional.empty());
    }
}
