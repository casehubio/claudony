package dev.claudony.casehub;

import dev.claudony.server.SessionRegistry;
import dev.claudony.server.TmuxService;
import dev.claudony.server.model.SessionStatus;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.spi.CaseChannelProvider;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Verifies the full SPI lifecycle sequence across ClaudonyWorkerProvisioner and
 * ClaudonyWorkerStatusListener using a real SessionRegistry so state transitions
 * are observable end-to-end rather than just method-call verified.
 *
 * <p>Known gap: caseId from ProvisionContext is not propagated to onWorkerStarted or
 * onWorkerCompleted. The status listener has no way to index workers by caseId, which
 * is why JpaCaseLineageQuery relies on CaseHub firing WORKER_EXECUTION_STARTED /
 * WORKER_EXECUTION_COMPLETED CaseLifecycleEvents with actorId=workerId (see #79).
 */
class WorkerLifecycleSequenceTest {

    // Real registry and mapping — lets us assert actual state transitions end-to-end.
    private final SessionRegistry registry = new SessionRegistry();
    private final WorkerSessionMapping sessionMapping = new WorkerSessionMapping();

    private TmuxService tmux;
    private ClaudonyWorkerProvisioner provisioner;
    private ClaudonyWorkerStatusListener listener;
    private Event<Object> events;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tmux = mock(TmuxService.class);
        events = mock(Event.class);

        var resolver = new WorkerCommandResolver(Map.of("default", "claude"));
        var contextProvider = mock(ClaudonyWorkerContextProvider.class);

        provisioner = new ClaudonyWorkerProvisioner(
                true, tmux, registry, resolver, contextProvider, sessionMapping, "/workspace");
        listener = new ClaudonyWorkerStatusListener(registry, tmux, events, sessionMapping);
    }

    @Test
    void happyPath_provisionThenActiveIdleThenStall() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final Worker worker = provisioner.provision(Set.of("default"), provisionContext(caseId));
        // Worker name is now the role/taskType ("code-reviewer"), not a UUID.
        // The tmux session UUID is tracked internally via WorkerSessionMapping.
        final String roleName = worker.getName();
        final String sessionId = sessionMapping.findByRole(roleName).orElseThrow();

        // After provision: session registered by UUID, starts IDLE
        assertThat(registry.find(sessionId)).isPresent();
        assertThat(registry.find(sessionId).get().status()).isEqualTo(SessionStatus.IDLE);
        verify(tmux).createSession(
                contains(ClaudonyWorkerProvisioner.SESSION_PREFIX), anyString(), anyString());

        // CaseEngine signals work started → ACTIVE (passes caseId in sessionMeta)
        listener.onWorkerStarted(roleName, Map.of("caseId", caseId.toString()));
        assertThat(registry.find(sessionId).get().status()).isEqualTo(SessionStatus.ACTIVE);

        // CaseEngine signals work completed normally → back to IDLE, session kept
        listener.onWorkerCompleted(roleName, WorkResult.completed("corr-1", Map.of(), roleName));
        assertThat(registry.find(sessionId).get().status()).isEqualTo(SessionStatus.IDLE);
        assertThat(registry.find(sessionId)).isPresent();

        // CaseEngine detects stall → event fired, tmux NOT killed (stall ≠ fault)
        listener.onWorkerStalled(roleName);
        verify(events).fire(new ClaudonyWorkerStatusListener.WorkerStalledEvent(roleName));
        verifyNoMoreInteractions(tmux); // no kill on stall
        assertThat(registry.find(sessionId)).isPresent();
    }

    @Test
    void faultPath_faultedWorkerIsKilledAndRemovedFromRegistry() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final Worker worker = provisioner.provision(Set.of("default"), provisionContext(caseId));
        final String roleName = worker.getName();
        final String sessionId = sessionMapping.findByRole(roleName).orElseThrow();

        listener.onWorkerStarted(roleName, Map.of("caseId", caseId.toString()));
        assertThat(registry.find(sessionId).get().status()).isEqualTo(SessionStatus.ACTIVE);

        // CaseEngine signals fault → tmux session killed, registry cleared
        listener.onWorkerCompleted(roleName, WorkResult.faulted("corr-1", roleName));

        verify(tmux).killSession(ClaudonyWorkerStatusListener.SESSION_PREFIX + sessionId);
        assertThat(registry.find(sessionId)).isEmpty();
    }

    @Test
    void twoWorkers_differentRoles_independentLifecycles() throws Exception {
        // Use two DIFFERENT roles — same-role concurrent workers are a known MVP limitation
        final UUID caseId = UUID.randomUUID();
        final Worker w1 = provisioner.provision(Set.of("default"), provisionContext(caseId));
        // Create a second worker with a different taskType
        final ProvisionContext ctx2 = new ProvisionContext(caseId, "reviewer",
                new io.casehub.api.model.WorkerContext("review", caseId, null, List.of(),
                        io.casehub.api.context.PropagationContext.createRoot(), Map.of()),
                io.casehub.api.context.PropagationContext.createRoot());
        final Worker w2 = provisioner.provision(Set.of("default"), ctx2);

        final String role1 = w1.getName();   // "code-reviewer"
        final String role2 = w2.getName();   // "reviewer"
        final String sid1 = sessionMapping.findByRole(role1).orElseThrow();
        final String sid2 = sessionMapping.findByRole(role2).orElseThrow();

        assertThat(sid1).isNotEqualTo(sid2);

        // Start both
        listener.onWorkerStarted(role1, Map.of("caseId", caseId.toString()));
        listener.onWorkerStarted(role2, Map.of("caseId", caseId.toString()));
        assertThat(registry.find(sid1).get().status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(registry.find(sid2).get().status()).isEqualTo(SessionStatus.ACTIVE);

        // Fault role1 — role2 must be unaffected
        listener.onWorkerCompleted(role1, WorkResult.faulted("corr-1", role1));
        assertThat(registry.find(sid1)).isEmpty();
        assertThat(registry.find(sid2).get().status()).isEqualTo(SessionStatus.ACTIVE);

        // Complete role2 normally
        listener.onWorkerCompleted(role2, WorkResult.completed("corr-2", Map.of(), role2));
        assertThat(registry.find(sid2).get().status()).isEqualTo(SessionStatus.IDLE);
    }

    @Test
    void workerContext_alwaysContainsMeshParticipationKey() {
        var contextProvider = new ClaudonyWorkerContextProvider(
                mock(CaseLineageQuery.class), mock(CaseChannelProvider.class));

        WorkerContext ctx = contextProvider.buildContext("worker-1", null,
                WorkRequest.of("researcher", Map.of()));

        assertThat(ctx.properties()).containsKey("meshParticipation");
    }

    private ProvisionContext provisionContext(final UUID caseId) {
        final var wc = new WorkerContext(
                "task", caseId, null, List.of(), PropagationContext.createRoot(), Map.of());
        return new ProvisionContext(caseId, "default", wc, PropagationContext.createRoot());
    }
}
