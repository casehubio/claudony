package dev.claudony.casehub;

import dev.claudony.server.SessionRegistry;
import dev.claudony.server.TmuxService;
import dev.claudony.server.model.SessionStatus;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.WorkerContext;
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

    // Real registry — lets us assert actual state transitions, not just method calls.
    private final SessionRegistry registry = new SessionRegistry();

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
                true, tmux, registry, resolver, contextProvider, "/workspace");
        listener = new ClaudonyWorkerStatusListener(registry, tmux, events);
    }

    @Test
    void happyPath_provisionThenActiveIdleThenStall() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final Worker worker = provisioner.provision(Set.of("default"), provisionContext(caseId));
        final String workerId = worker.getName();

        // After provision: session registered, starts IDLE
        assertThat(registry.find(workerId)).isPresent();
        assertThat(registry.find(workerId).get().status()).isEqualTo(SessionStatus.IDLE);
        verify(tmux).createSession(
                contains(ClaudonyWorkerProvisioner.SESSION_PREFIX), anyString(), anyString());

        // CaseEngine signals work has started → ACTIVE
        listener.onWorkerStarted(workerId, Map.of());
        assertThat(registry.find(workerId).get().status()).isEqualTo(SessionStatus.ACTIVE);

        // CaseEngine signals work completed normally → back to IDLE, session kept
        listener.onWorkerCompleted(workerId, WorkResult.completed("corr-1", Map.of(), workerId));
        assertThat(registry.find(workerId).get().status()).isEqualTo(SessionStatus.IDLE);
        assertThat(registry.find(workerId)).isPresent();

        // CaseEngine detects stall → event fired, tmux NOT killed (stall ≠ fault)
        listener.onWorkerStalled(workerId);
        verify(events).fire(new ClaudonyWorkerStatusListener.WorkerStalledEvent(workerId));
        verifyNoMoreInteractions(tmux); // no kill on stall
        assertThat(registry.find(workerId)).isPresent();
    }

    @Test
    void faultPath_faultedWorkerIsKilledAndRemovedFromRegistry() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final Worker worker = provisioner.provision(Set.of("default"), provisionContext(caseId));
        final String workerId = worker.getName();

        listener.onWorkerStarted(workerId, Map.of());
        assertThat(registry.find(workerId).get().status()).isEqualTo(SessionStatus.ACTIVE);

        // CaseEngine signals fault → session killed, removed from registry
        listener.onWorkerCompleted(workerId, WorkResult.faulted("corr-1", workerId));

        verify(tmux).killSession(ClaudonyWorkerStatusListener.SESSION_PREFIX + workerId);
        assertThat(registry.find(workerId)).isEmpty();
    }

    @Test
    void twoWorkers_independentLifecycles_doNotInterfere() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final Worker w1 = provisioner.provision(Set.of("default"), provisionContext(caseId));
        final Worker w2 = provisioner.provision(Set.of("default"), provisionContext(caseId));
        final String id1 = w1.getName();
        final String id2 = w2.getName();

        assertThat(id1).isNotEqualTo(id2);

        // Start both
        listener.onWorkerStarted(id1, Map.of());
        listener.onWorkerStarted(id2, Map.of());
        assertThat(registry.find(id1).get().status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(registry.find(id2).get().status()).isEqualTo(SessionStatus.ACTIVE);

        // Fault w1 — w2 must be unaffected
        listener.onWorkerCompleted(id1, WorkResult.faulted("corr-1", id1));
        assertThat(registry.find(id1)).isEmpty();
        assertThat(registry.find(id2).get().status()).isEqualTo(SessionStatus.ACTIVE);

        // Complete w2 normally
        listener.onWorkerCompleted(id2, WorkResult.completed("corr-2", Map.of(), id2));
        assertThat(registry.find(id2).get().status()).isEqualTo(SessionStatus.IDLE);
    }

    private ProvisionContext provisionContext(final UUID caseId) {
        final var wc = new WorkerContext(
                "task", caseId, null, List.of(), PropagationContext.createRoot(), Map.of());
        return new ProvisionContext(caseId, "default", wc, PropagationContext.createRoot());
    }
}
