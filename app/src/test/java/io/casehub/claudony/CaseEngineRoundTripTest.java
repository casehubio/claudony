package io.casehub.claudony;

import io.casehub.api.model.WorkerSummary;
import io.casehub.claudony.casehub.ClaudonyWorkerExecutionManager;
import io.casehub.claudony.casehub.JpaCaseLineageQuery;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import io.casehub.claudony.server.TmuxService;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CaseEngine round-trip integration test.
 *
 * Exercises the full engine entry point: CaseHub.startCase() → CaseStartedEventHandler
 * (blocking=true, engine#367) → CONTEXT_CHANGED → CaseContextChangedEventHandler evaluates
 * ContextChangeTrigger → ClaudonyReactiveWorkerProvisioner.provision() (TmuxService mocked) →
 * WorkflowExecutionCompleted published → ClaudonyLedgerEventCapture writes ledger →
 * JpaCaseLineageQuery.findCompletedWorkers() returns populated WorkerSummary.
 *
 * CaseStartedEventHandler runs on a blocking thread (engine#367 — blocking=true). Quartz uses
 * the RAM store (quartz.store-type=ram) to avoid JTA JDBC on the blocking thread.
 *
 * CDI-only — no HTTP endpoints exercised, no @TestSecurity (PP-20260513-7c227e).
 *
 * Closes #92 Closes #113 Refs #367
 */
@QuarkusTest
@TestProfile(CaseEngineRoundTripTest.CasehubEnabledProfile.class)
public class CaseEngineRoundTripTest {

    public static class CasehubEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "claudony.casehub.enabled", "true",
                    "claudony.casehub.workers.provider-config.agent.command", "claude",
                    // Fast poll for tests — watcher detects exit in ≤200ms rather than ≤5000ms
                    "claudony.casehub.worker-exit-poll-ms", "200",
                    // Suppress ServerStartup.checkTmux() — @InjectMock replaces TmuxService
                    // after CDI init, but StartupEvent fires during init (before mock is active).
                    // Agent mode skips ServerStartup.onStart() entirely without affecting engine.
                    "claudony.mode", "agent",
                    // Index casehub-engine so all engine CDI beans (CaseHubRuntimeImpl,
                    // CaseContextChangedEventHandler, etc.) are visible to Quarkus.
                    "quarkus.index-dependency.casehub-engine.group-id", "io.casehub",
                    "quarkus.index-dependency.casehub-engine.artifact-id", "casehub-engine",
                    "quarkus.index-dependency.neocortex-memory.group-id", "io.casehub",
                    "quarkus.index-dependency.neocortex-memory.artifact-id", "casehub-neocortex-memory",
                    // Mirrors %test.quarkus.arc.exclude-types but re-includes TestAgentCase.
                    // Uses globs for engine sub-packages where no beans are needed;
                    // individual exclusions for handler package (some handlers kept).
                    "quarkus.arc.exclude-types",
                    // --- ledger/persistence ---
                    "io.casehub.ledger.repository.CaseLedgerEntryRepository,"
                    + "io.casehub.ledger.service.CaseLedgerEventCapture,"
                    + "io.casehub.ledger.service.WorkerDecisionEventCapture,"
                    + "io.casehub.ledger.runtime.service.DefaultOutcomeRecorder,"
                    + "io.casehub.ledger.runtime.service.intercept.AuditedInterceptor,"
                    + "io.casehub.persistence.memory.InMemoryCaseInstanceRepository,"
                    + "io.casehub.persistence.memory.InMemoryCaseMetaModelRepository,"
                    + "io.casehub.persistence.memory.InMemoryEventLogRepository,"
                    + "io.casehub.testing.WorkResultSubmitter,"
                    + "io.casehub.testing.TestWorkerProvisioner,"
                    // --- engine sub-packages (globs) ---
                    + "io.casehub.engine.internal.bridge.*,"
                    + "io.casehub.engine.internal.callback.*,"
                    + "io.casehub.engine.internal.orchestration.*,"
                    + "io.casehub.engine.internal.engine.recovery.*,"
                    + "io.casehub.engine.scheduler.**,"
                    + "io.casehub.engine.trust.**,"
                    + "io.casehub.connectors.**,"
                    + "io.casehub.work.core.**,"
                    // --- runtime-core handlers NOT needed ---
                    + "io.casehub.engine.internal.engine.handler.CaseStatusChangedHandler,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateApprovedHandler,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateExpiredHandler,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateRejectedHandler,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneActivatedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneCompletedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.WorkerScheduleEventHandler,"
                    // --- runtime EventBusAdapters for excluded handlers ---
                    + "io.casehub.engine.internal.engine.handler.CaseStatusChangedEventBusAdapter,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateApprovedEventBusAdapter,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateExpiredEventBusAdapter,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateRejectedEventBusAdapter,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneActivatedEventBusAdapter,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneCompletedEventBusAdapter,"
                    + "io.casehub.engine.internal.engine.handler.WorkerScheduleEventBusAdapter,"
                    // --- claudony / qhorus ---
                    + "io.casehub.claudony.casehub.AgentCase,"
                    + "io.casehub.claudony.NoOpWorkerExecutionRecoveryService,"
                    + "io.casehub.qhorus.runtime.store.jpa.**,"
                    + "io.casehub.qhorus.runtime.identity.CrossTenantProducer,"
                    + "io.casehub.qhorus.runtime.identity.QhorusInboundCurrentPrincipal,"
                    + "io.casehub.qhorus.runtime.api.A2AResource,"
                    + "io.casehub.qhorus.runtime.api.AgentCardResource,"
                    + "io.casehub.qhorus.runtime.api.CausalGraphResource,"
                    + "io.casehub.qhorus.push.QhorusPushWebSocket"
            );
        }
    }

    @Inject TestAgentCase agentCase;
    @Inject JpaCaseLineageQuery lineageQuery;
    @Inject SessionRegistry sessionRegistry;
    @Inject @WorkerBackend ClaudonyWorkerExecutionManager execManager;
    @Inject Event<CaseLifecycleEvent> lifecycleEvents;

    @InjectMock TmuxService tmuxService;

    @Test
    void startCase_engineProvisions_watcherDetectsExit_andLineageReturnsCompletedSummary()
            throws Exception {
        // provision() now auto-starts the watcher via startWatcherForSession(). Stub sessionExists()
        // to return true initially so the watcher polls rather than detecting immediate exit and
        // removing the session from the registry before we can inspect it.
        when(tmuxService.sessionExists(anyString())).thenReturn(true);

        UUID caseId = agentCase.startCase();

        // Wait for provision() → createWorkerSession() — and registry.register() which follows synchronously.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        verify(tmuxService, atLeastOnce())
                                .createWorkerSession(anyString(), anyString(), anyString()));

        // Assert session is in registry (startWatcherForSession() auto-started the watcher above).
        var session = sessionRegistry.findByCaseId(caseId.toString()).get(0);
        assertThat(session).isNotNull();

        // Simulate WorkerExecutionStarted BEFORE triggering exit.
        // Lineage resolves the worker name from the preceding Started entry (engine#390:
        // WorkerExecutionCompleted carries actorId="system", not the worker name).
        lifecycleEvents.fireAsync(CaseLifecycleEvent.of(
                caseId, "default", "ExecuteWorker", "WorkerExecutionStarted", "ACTIVE",
                "agent", "WORKER", null)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Trigger watcher exit: sessionExists()→false causes the auto-started watcher to detect
        // session gone and publish WorkflowExecutionCompleted.
        when(tmuxService.sessionExists(anyString())).thenReturn(false);

        // Wait for: watcher publishes completion → WorkflowExecutionCompletedHandler processes it
        // → fireAsync(CaseLifecycleEvent WorkerExecutionCompleted) → ClaudonyLedgerEventCapture writes ledger
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<WorkerSummary> workers = lineageQuery.findCompletedWorkers(caseId);
                    assertThat(workers)
                            .as("lineage must contain the completed worker")
                            .hasSize(1);
                });

        WorkerSummary summary = lineageQuery.findCompletedWorkers(caseId).get(0);
        assertThat(summary.workerName()).as("workerName").isEqualTo("agent");
        assertThat(summary.workerId()).as("workerId").isEqualTo("agent");
        assertThat(summary.startedAt()).as("startedAt").isNotNull();
        assertThat(summary.completedAt()).as("completedAt").isNotNull();
        assertThat(summary.ledgerEntryId()).as("ledgerEntryId").isNotNull();
    }
}
