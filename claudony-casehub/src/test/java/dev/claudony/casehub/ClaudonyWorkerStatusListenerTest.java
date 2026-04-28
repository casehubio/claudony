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
    private WorkerSessionMapping sessionMapping;
    private ClaudonyWorkerStatusListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = mock(SessionRegistry.class);
        tmux = mock(TmuxService.class);
        sessionMapping = new WorkerSessionMapping();
        Event<Object> events = mock(Event.class);
        listener = new ClaudonyWorkerStatusListener(registry, tmux, events, sessionMapping);
    }

    @Test
    void onWorkerStarted_updatesSessionToActive() {
        // roleName="code-reviewer", sessionId="session-uuid-123" registered in mapping
        sessionMapping.register("code-reviewer", null, "session-uuid-123");
        when(registry.find("session-uuid-123")).thenReturn(Optional.of(session("session-uuid-123", SessionStatus.IDLE)));

        listener.onWorkerStarted("code-reviewer", java.util.Map.of());

        verify(registry).updateStatus("session-uuid-123", SessionStatus.ACTIVE);
    }

    @Test
    void onWorkerStarted_usesCaseIdForPreciseLookup() {
        var caseId = java.util.UUID.randomUUID();
        sessionMapping.register("researcher", caseId, "uuid-researcher-1");
        when(registry.find("uuid-researcher-1")).thenReturn(Optional.of(session("uuid-researcher-1", SessionStatus.IDLE)));

        listener.onWorkerStarted("researcher", java.util.Map.of("caseId", caseId.toString()));

        verify(registry).updateStatus("uuid-researcher-1", SessionStatus.ACTIVE);
    }

    @Test
    void onWorkerStarted_noMappingFound_isNoOp() {
        // no mapping registered for "unknown"
        assertThatNoException().isThrownBy(() ->
                listener.onWorkerStarted("unknown", java.util.Map.of()));
        verify(registry, never()).updateStatus(any(), any());
    }

    @Test
    void onWorkerCompleted_completedResult_updatesSessionToIdle() {
        sessionMapping.register("analyst", null, "session-analyst-1");
        when(registry.find("session-analyst-1")).thenReturn(Optional.of(session("session-analyst-1", SessionStatus.ACTIVE)));
        var result = WorkResult.completed("corr-1", java.util.Map.of(), "analyst");

        listener.onWorkerCompleted("analyst", result);

        verify(registry).updateStatus("session-analyst-1", SessionStatus.IDLE);
    }

    @Test
    void onWorkerCompleted_faultedResult_terminatesSession() throws Exception {
        sessionMapping.register("code-reviewer", null, "session-cr-1");
        when(registry.find("session-cr-1")).thenReturn(Optional.of(session("session-cr-1", SessionStatus.ACTIVE)));
        var result = WorkResult.faulted("corr-1", "code-reviewer");

        listener.onWorkerCompleted("code-reviewer", result);

        verify(tmux).killSession(ClaudonyWorkerStatusListener.SESSION_PREFIX + "session-cr-1");
        verify(registry).remove("session-cr-1");
    }

    @Test
    void onWorkerCompleted_faultedAndTerminateFails_stillRemovesFromRegistry() throws Exception {
        sessionMapping.register("worker-x", null, "session-x-1");
        when(registry.find("session-x-1")).thenReturn(Optional.of(session("session-x-1", SessionStatus.ACTIVE)));
        doThrow(new java.io.IOException("tmux gone")).when(tmux).killSession(anyString());
        var result = WorkResult.faulted("corr-1", "worker-x");

        assertThatNoException().isThrownBy(() -> listener.onWorkerCompleted("worker-x", result));
        verify(registry).remove("session-x-1");
    }

    @Test
    void onWorkerCompleted_noMappingFound_isNoOp() {
        var result = WorkResult.completed("corr-1", java.util.Map.of(), "ghost-role");

        assertThatNoException().isThrownBy(() -> listener.onWorkerCompleted("ghost-role", result));
        verify(registry, never()).updateStatus(any(), any());
    }

    @Test
    void onWorkerStalled_doesNotTerminateSession() throws Exception {
        listener.onWorkerStalled("worker-stalled");
        verifyNoInteractions(tmux);
    }

    private Session session(String id, SessionStatus status) {
        return new Session(id, ClaudonyWorkerStatusListener.SESSION_PREFIX + id, "/tmp", "claude",
                status, Instant.now(), Instant.now(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
