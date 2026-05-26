package io.casehub.claudony;

import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerSummary;
import io.casehub.claudony.casehub.JpaCaseLineageQuery;
import io.casehub.claudony.server.TmuxService;
import io.casehub.engine.internal.event.EventBusAddresses;
import io.casehub.engine.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.internal.model.CaseInstance;
import io.casehub.engine.spi.CaseInstanceRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.mutiny.core.eventbus.EventBus;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

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
class CaseEngineRoundTripTest {

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
                    // Quartz RAM store — no JTA JDBC, no Quartz tables.
                    // Required for CaseStartedEventHandler.registerScheduledTriggers() to run
                    // on the blocking thread without a JDBC datasource (PP-20260516-quartz-ram).
                    "quarkus.quartz.store-type", "ram",
                    // Mirrors %test.quarkus.arc.exclude-types from application.properties but
                    // re-includes TestResearcherCase and NoOpWorkloadProvider (needed for the
                    // engine round-trip). CaseStartedEventHandler and SchedulerService are
                    // now included — blocking=true (engine#367) makes the handler safe on a
                    // blocking thread; RAM store makes SchedulerService work without JTA JDBC.
                    "quarkus.arc.exclude-types",
                    "io.casehub.ledger.repository.CaseLedgerEntryRepository,"
                    + "io.casehub.ledger.service.CaseLedgerEventCapture,"
                    + "io.casehub.persistence.memory.InMemoryCaseInstanceRepository,"
                    + "io.casehub.persistence.memory.InMemoryCaseMetaModelRepository,"
                    + "io.casehub.persistence.memory.InMemoryEventLogRepository,"
                    + "io.casehub.testing.WorkResultSubmitter,"
                    + "io.casehub.engine.internal.engine.handler.CaseStatusChangedHandler,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneActivatedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneCompletedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.SignalReceivedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.WorkerScheduleEventHandler,"
                    + "io.casehub.engine.internal.engine.recovery.DefaultWorkerExecutionRecoveryService,"
                    + "io.casehub.engine.internal.orchestration.WorkOrchestrator,"
                    + "io.casehub.engine.internal.worker.CasehubWorkloadProvider,"
                    + "io.casehub.work.core.strategy.RoundRobinStrategy"
            );
        }
    }

    @Inject TestResearcherCase researcherCase;
    @Inject JpaCaseLineageQuery lineageQuery;
    @Inject CaseInstanceRepository caseInstanceRepository;
    @Inject EventBus eventBus;

    @InjectMock TmuxService tmuxService;

    @Test
    void startCase_engineProvisions_andLineageReturnsCompletedSummary() throws Exception {
        doNothing().when(tmuxService).createSession(anyString(), anyString(), anyString());

        // Start via the true engine entry point. CaseStartedEventHandler (blocking=true)
        // handles CASE_STARTED, registers Quartz triggers (RAM store), fires CONTEXT_CHANGED.
        UUID caseId = researcherCase.startCase(Map.of("topic", "test-topic"))
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        // Wait for ClaudonyReactiveWorkerProvisioner.provision() → tmuxService.createSession()
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        verify(tmuxService, atLeastOnce())
                                .createSession(anyString(), anyString(), anyString()));

        // Drive completion: publish WorkflowExecutionCompleted to the engine event bus.
        CaseInstance instance = caseInstanceRepository.findByUuid(caseId)
                .await().atMost(Duration.ofSeconds(5));
        Capability cap = new Capability("researcher", "{}", "{}");
        Worker provisioned = new Worker("researcher", List.of(cap), ctx -> Map.of());

        eventBus.publish(
                EventBusAddresses.WORKER_EXECUTION_FINISHED,
                new WorkflowExecutionCompleted(
                        instance, provisioned, UUID.randomUUID().toString(), Map.of()));

        // Wait for ClaudonyLedgerEventCapture (@ObservesAsync) to write the ledger entry.
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
