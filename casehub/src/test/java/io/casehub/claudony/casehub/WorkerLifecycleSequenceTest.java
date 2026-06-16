package io.casehub.claudony.casehub;

import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.spi.ReactiveCaseChannelProvider;
import io.smallrye.mutiny.Uni;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Verifies the full SPI lifecycle sequence across ClaudonyReactiveWorkerProvisioner and
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
    private final SessionRegistry      registry       = new SessionRegistry();
    private final WorkerSessionMapping sessionMapping = new WorkerSessionMapping();

    private TmuxService                          tmux;
    private ClaudonyReactiveWorkerProvisioner    provisioner;
    private ClaudonyWorkerStatusListener         listener;
    private Event<Object>                        events;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tmux = mock(TmuxService.class);
        events = mock(Event.class);

        var resolver = new WorkerCommandResolver(Map.of("default", "claude"));

        provisioner = new ClaudonyReactiveWorkerProvisioner(
                true, tmux, registry, resolver, sessionMapping, "/workspace", null, null, null);
        listener = new ClaudonyWorkerStatusListener(registry, tmux, events, sessionMapping);
    }

    @Test
    void happyPath_provisionThenActiveIdleThenStall() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final ProvisionContext ctx = provisionContext(caseId);
        provisioner.provision(Set.of("default"), ctx).await().indefinitely();
        // Role name comes from the taskType in the context.
        final String roleName = ctx.taskType();
        final String sessionId = sessionMapping.findByRole(roleName).orElseThrow();

        // After provision: session registered by UUID, starts IDLE
        assertThat(registry.find(sessionId)).isPresent();
        assertThat(registry.find(sessionId).get().status()).isEqualTo(SessionStatus.IDLE);
        verify(tmux).createWorkerSession(
                contains(ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX), anyString(), anyString());

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
        verify(tmux, never()).killSession(anyString()); // stall does not kill the session
        assertThat(registry.find(sessionId)).isPresent();
    }

    @Test
    void faultPath_faultedWorkerIsKilledAndRemovedFromRegistry() throws Exception {
        final UUID caseId = UUID.randomUUID();
        final ProvisionContext ctx = provisionContext(caseId);
        provisioner.provision(Set.of("default"), ctx).await().indefinitely();
        final String roleName = ctx.taskType();
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
        final ProvisionContext ctx1 = provisionContext(caseId);
        provisioner.provision(Set.of("default"), ctx1).await().indefinitely();
        // Create a second worker with a different taskType
        final ProvisionContext ctx2 = new ProvisionContext(caseId, "reviewer",
                new io.casehub.api.model.WorkerContext("review", caseId, null, List.of(),
                        io.casehub.api.context.PropagationContext.createRoot(), Map.of()),
                io.casehub.api.context.PropagationContext.createRoot(), null, null);
        provisioner.provision(Set.of("default"), ctx2).await().indefinitely();

        final String role1 = ctx1.taskType();   // "default"
        final String role2 = ctx2.taskType();   // "reviewer"
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
        CaseLineageQuery lineageQuery = mock(CaseLineageQuery.class);
        ReactiveCaseChannelProvider channelProvider = mock(ReactiveCaseChannelProvider.class);
        when(lineageQuery.findCompletedWorkers(any())).thenReturn(Uni.createFrom().item(List.of()));
        when(channelProvider.listChannels(any())).thenReturn(Uni.createFrom().item(List.of()));

        var contextProvider = new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider);

        WorkerContext ctx = contextProvider.buildContext("worker-1", null,
                WorkRequest.of("researcher", Map.of()))
                .await().indefinitely();

        assertThat(ctx.properties()).containsKey("meshParticipation");
    }

    private ProvisionContext provisionContext(final UUID caseId) {
        final var wc = new WorkerContext(
                "task", caseId, null, List.of(), PropagationContext.createRoot(), Map.of());
        return new ProvisionContext(caseId, "default", wc, PropagationContext.createRoot(), null, null);
    }
}
