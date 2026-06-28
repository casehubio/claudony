package io.casehub.claudony.casehub;

import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.WorkerCaseLifecycleEvent;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
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
    private TmuxService                  tmux;
    private WorkerSessionMapping         sessionMapping;
    private Event<Object>                events;
    private ClaudonyWorkerStatusListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = mock(SessionRegistry.class);
        tmux = mock(TmuxService.class);
        sessionMapping = new WorkerSessionMapping();
        events = mock(Event.class);
        listener = new ClaudonyWorkerStatusListener(registry, tmux, events, sessionMapping);
    }

    @Test
    void onWorkerStarted_updatesSessionToActive() {
        // roleName="code-reviewer", sessionId="session-uuid-123" registered in mapping
        sessionMapping.register("code-reviewer", null, "session-uuid-123");
        when(registry.findUnscoped("session-uuid-123")).thenReturn(Optional.of(session("session-uuid-123", SessionStatus.IDLE)));

        listener.onWorkerStarted("code-reviewer", java.util.Map.of());

        verify(registry).updateStatus("session-uuid-123", SessionStatus.ACTIVE);
    }

    @Test
    void onWorkerStarted_usesCaseIdForPreciseLookup() {
        var caseId = java.util.UUID.randomUUID();
        sessionMapping.register("agent", caseId, "uuid-agent-1");
        when(registry.findUnscoped("uuid-agent-1")).thenReturn(Optional.of(session("uuid-agent-1", SessionStatus.IDLE)));

        listener.onWorkerStarted("agent", java.util.Map.of("caseId", caseId.toString()));

        verify(registry).updateStatus("uuid-agent-1", SessionStatus.ACTIVE);
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
        when(registry.findUnscoped("session-analyst-1")).thenReturn(Optional.of(session("session-analyst-1", SessionStatus.ACTIVE)));
        var result = WorkResult.completed("corr-1", java.util.Map.of(), "analyst");

        listener.onWorkerCompleted("analyst", result);

        verify(registry).updateStatus("session-analyst-1", SessionStatus.IDLE);
    }

    @Test
    void onWorkerCompleted_faultedResult_terminatesSession() throws Exception {
        sessionMapping.register("code-reviewer", null, "session-cr-1");
        when(registry.findUnscoped("session-cr-1")).thenReturn(Optional.of(session("session-cr-1", SessionStatus.ACTIVE)));
        var result = WorkResult.faulted("corr-1", "code-reviewer");

        listener.onWorkerCompleted("code-reviewer", result);

        verify(tmux).killSession(ClaudonyWorkerStatusListener.SESSION_PREFIX + "session-cr-1");
        verify(registry).remove("session-cr-1");
    }

    @Test
    void onWorkerCompleted_faultedAndTerminateFails_stillRemovesFromRegistry() throws Exception {
        sessionMapping.register("worker-x", null, "session-x-1");
        when(registry.findUnscoped("session-x-1")).thenReturn(Optional.of(session("session-x-1", SessionStatus.ACTIVE)));
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
    void onWorkerCompleted_withCaseId_usesPreciseLookup() {
        var caseId = java.util.UUID.randomUUID();
        sessionMapping.register("analyst", caseId, "session-analyst-precise");
        when(registry.findUnscoped("session-analyst-precise"))
                .thenReturn(Optional.of(session("session-analyst-precise", SessionStatus.ACTIVE)));
        var result = WorkResult.completed("corr-1", java.util.Map.of(), "analyst", caseId);

        listener.onWorkerCompleted("analyst", result);

        verify(registry).updateStatus("session-analyst-precise", SessionStatus.IDLE);
    }

    @Test
    void onWorkerCompleted_concurrentSameRole_correctSessionUpdated() {
        // Two concurrent "code-reviewer" workers on different cases
        var caseA = java.util.UUID.randomUUID();
        var caseB = java.util.UUID.randomUUID();
        sessionMapping.register("code-reviewer", caseA, "session-cr-case-a");
        sessionMapping.register("code-reviewer", caseB, "session-cr-case-b");

        when(registry.findUnscoped("session-cr-case-a"))
                .thenReturn(Optional.of(session("session-cr-case-a", SessionStatus.ACTIVE)));
        when(registry.findUnscoped("session-cr-case-b"))
                .thenReturn(Optional.of(session("session-cr-case-b", SessionStatus.ACTIVE)));

        // Case A completes
        listener.onWorkerCompleted("code-reviewer", WorkResult.completed("corr-a", java.util.Map.of(), "code-reviewer", caseA));
        verify(registry).updateStatus("session-cr-case-a", SessionStatus.IDLE);
        verify(registry, never()).updateStatus("session-cr-case-b", SessionStatus.IDLE);

        // Case B completes
        listener.onWorkerCompleted("code-reviewer", WorkResult.completed("corr-b", java.util.Map.of(), "code-reviewer", caseB));
        verify(registry).updateStatus("session-cr-case-b", SessionStatus.IDLE);
    }

    @Test
    void onWorkerCompleted_withNullCaseId_fallsBackToByRole() {
        // No caseId in result — falls back to byRole (legacy/external callers)
        sessionMapping.register("writer", null, "session-writer-1");
        when(registry.findUnscoped("session-writer-1"))
                .thenReturn(Optional.of(session("session-writer-1", SessionStatus.ACTIVE)));
        var result = WorkResult.completed("corr-1", java.util.Map.of(), "writer");

        listener.onWorkerCompleted("writer", result);

        verify(registry).updateStatus("session-writer-1", SessionStatus.IDLE);
    }

    @Test
    void onWorkerStalled_doesNotTerminateSession() throws Exception {
        listener.onWorkerStalled("worker-stalled");
        verifyNoInteractions(tmux);
    }

    @Test
    void onWorkerStarted_firesCaseLifecycleEvent_whenCaseIdPresent() {
        var caseId = java.util.UUID.randomUUID();
        sessionMapping.register("analyst", caseId, "session-uuid-event-test");
        var firedEvents = new java.util.ArrayList<WorkerCaseLifecycleEvent>();
        doAnswer(inv -> { firedEvents.add(inv.getArgument(0)); return null; })
                .when(events).fire(any(WorkerCaseLifecycleEvent.class));

        listener.onWorkerStarted("analyst", java.util.Map.of("caseId", caseId.toString()));

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).caseId()).isEqualTo(caseId.toString());
        assertThat(firedEvents.get(0).tenancyId()).isEqualTo(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void onWorkerStarted_firesCaseLifecycleEvent_withDefaultTenancyId_whenSessionNotFound() {
        var caseId = java.util.UUID.randomUUID();
        var firedEvents = new java.util.ArrayList<WorkerCaseLifecycleEvent>();
        doAnswer(inv -> { firedEvents.add(inv.getArgument(0)); return null; })
                .when(events).fire(any(WorkerCaseLifecycleEvent.class));

        listener.onWorkerStarted("ghost", java.util.Map.of("caseId", caseId.toString()));

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).tenancyId()).isEqualTo(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void onWorkerCompleted_firesCaseLifecycleEvent_whenCaseIdPresent() {
        var caseId = java.util.UUID.randomUUID();
        sessionMapping.register("analyst", caseId, "session-uuid-event-2");
        var firedEvents = new java.util.ArrayList<WorkerCaseLifecycleEvent>();
        doAnswer(inv -> { firedEvents.add(inv.getArgument(0)); return null; })
                .when(events).fire(any(WorkerCaseLifecycleEvent.class));

        listener.onWorkerCompleted("analyst",
                WorkResult.completed("corr", java.util.Map.of(), "analyst", caseId));

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).caseId()).isEqualTo(caseId.toString());
        assertThat(firedEvents.get(0).tenancyId()).isEqualTo(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void onWorkerStarted_doesNotFireEvent_whenNoCaseId() {
        sessionMapping.register("analyst", null, "session-no-case");
        listener.onWorkerStarted("analyst", java.util.Map.of());
        verify(events, never()).fire(any(WorkerCaseLifecycleEvent.class));
    }

    private Session session(String id, SessionStatus status) {
        return new Session(id, ClaudonyWorkerStatusListener.SESSION_PREFIX + id, "/tmp", "claude",
                status, Instant.now(), Instant.now(), Optional.empty(), Optional.empty(), Optional.empty(),
                io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID);
    }
}
