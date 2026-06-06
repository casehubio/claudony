package io.casehub.claudony;

import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerSummary;
import io.casehub.claudony.casehub.ClaudonyReactiveWorkerProvisioner;
import io.casehub.claudony.casehub.ClaudonyWorkerExecutionManager;
import io.casehub.claudony.casehub.JpaCaseLineageQuery;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
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
                    "claudony.casehub.workers.commands.researcher", "claude",
                    "claudony.casehub.workers.commands.default", "claude",
                    // Suppress ServerStartup.checkTmux() — @InjectMock replaces TmuxService
                    // after CDI init, but StartupEvent fires during init (before mock is active).
                    // Agent mode skips ServerStartup.onStart() entirely without affecting engine.
                    "claudony.mode", "agent",
                    // Index casehub-engine so all engine CDI beans (CaseHubRuntimeImpl,
                    // CaseContextChangedEventHandler, etc.) are visible to Quarkus.
                    "quarkus.index-dependency.casehub-engine.group-id", "io.casehub",
                    "quarkus.index-dependency.casehub-engine.artifact-id", "casehub-engine",
                    // Mirrors %test.quarkus.arc.exclude-types from application.properties but
                    // re-includes TestResearcherCase (needed for the engine round-trip).
                    // CaseStartedEventHandler and SchedulerService are now included —
                    // blocking=true (engine#367) makes the handler safe on a blocking thread;
                    // NoOpJobScheduler (@DefaultBean) satisfies JobScheduler injection since
                    // TestResearcherCase has no schedule bindings (no-op safe).
                    "quarkus.arc.exclude-types",
                    "io.casehub.ledger.repository.CaseLedgerEntryRepository,"
                    + "io.casehub.ledger.service.CaseLedgerEventCapture,"
                    + "io.casehub.ledger.service.WorkerDecisionEventCapture,"
                    + "io.casehub.persistence.memory.InMemoryCaseInstanceRepository,"
                    + "io.casehub.persistence.memory.InMemoryCaseMetaModelRepository,"
                    + "io.casehub.persistence.memory.InMemoryEventLogRepository,"
                    + "io.casehub.testing.WorkResultSubmitter,"
                    + "io.casehub.engine.internal.engine.handler.CaseStatusChangedHandler,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateApprovedHandler,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateExpiredHandler,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateRejectedHandler,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneActivatedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneCompletedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.SignalReceivedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.WorkerScheduleEventHandler,"
                    + "io.casehub.engine.internal.engine.recovery.DefaultWorkerExecutionRecoveryService,"
                    + "io.casehub.engine.internal.orchestration.WorkOrchestrator,"
                    + "io.casehub.work.core.strategy.RoundRobinStrategy,"
                    + "io.casehub.claudony.casehub.ResearcherCase"
            );
        }
    }

    @Inject TestResearcherCase researcherCase;
    @Inject JpaCaseLineageQuery lineageQuery;
    @Inject SessionRegistry sessionRegistry;
    @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;
    @Inject ClaudonyWorkerExecutionManager execManager;
    @Inject Event<CaseLifecycleEvent> lifecycleEvents;

    @InjectMock TmuxService tmuxService;

    @Test
    void startCase_engineProvisions_watcherDetectsExit_andLineageReturnsCompletedSummary()
            throws Exception {
        UUID caseId = researcherCase.startCase(Map.of("topic", "test-topic"))
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        // Wait for provision() → createWorkerSession()
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        verify(tmuxService, atLeastOnce())
                                .createWorkerSession(anyString(), anyString(), anyString()));

        // WorkOrchestrator is excluded from CasehubEnabledProfile (it needs AgentRoutingStrategy etc.).
        // Start the watcher manually by calling watch() directly — equivalent to WorkerExecutionManager.submit().
        var session = sessionRegistry.findByCaseId(caseId.toString()).get(0);
        var instance = caseInstanceRepository.findByUuid(caseId).await().atMost(Duration.ofSeconds(5));
        var cap = new Capability("researcher", "{}", "{}");
        var worker = new Worker("researcher", List.of(cap), ctx -> Map.of());

        // Simulate WorkerExecutionStarted BEFORE starting the watcher.
        // Lineage resolves the worker name from the preceding Started entry (engine#390:
        // WorkerExecutionCompleted carries actorId="system", not the worker name).
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                caseId, null, "ExecuteWorker", "WorkerExecutionStarted", "ACTIVE",
                "researcher", "WORKER", null)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Now start the watcher. sessionExists()→false triggers immediate completion publish.
        when(tmuxService.sessionExists(anyString())).thenReturn(false);
        execManager.watch(session.id(), session.name(), instance, worker);

        // Wait for: watcher publishes completion → WorkflowExecutionCompletedHandler processes it
        // → fireAsync(CaseLifecycleEvent WorkerExecutionCompleted) → ClaudonyLedgerEventCapture writes ledger
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<WorkerSummary> workers = lineageQuery.findCompletedWorkers(caseId)
                            .await().atMost(Duration.ofSeconds(5));
                    assertThat(workers)
                            .as("lineage must contain the completed worker")
                            .hasSize(1);
                });

        WorkerSummary summary = lineageQuery.findCompletedWorkers(caseId)
                .await().atMost(Duration.ofSeconds(5))
                .get(0);
        assertThat(summary.workerName()).as("workerName").isEqualTo("researcher");
        assertThat(summary.workerId()).as("workerId").isEqualTo("researcher");
        assertThat(summary.startedAt()).as("startedAt").isNotNull();
        assertThat(summary.completedAt()).as("completedAt").isNotNull();
        assertThat(summary.ledgerEntryId()).as("ledgerEntryId").isNotNull();
    }
}
