package io.casehub.claudony;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerSummary;
import io.casehub.claudony.casehub.ClaudonyWorkerContextProvider;
import io.casehub.claudony.casehub.JpaCaseLineageQuery;
import io.casehub.claudony.server.TmuxService;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.engine.internal.event.CaseContextChangedEvent;
import io.casehub.engine.internal.event.EventBusAddresses;
import io.casehub.engine.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.internal.model.CaseInstance;
import io.casehub.engine.internal.model.CaseMetaModel;
import io.casehub.engine.spi.CaseDefinitionRegistry;
import io.casehub.engine.spi.CaseInstanceRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CaseEngine round-trip integration test.
 *
 * Exercises: CaseContextChangedEvent → CaseContextChangedEventHandler evaluates
 * ContextChangeTrigger → ClaudonyWorkerProvisioner.provision() (TmuxService mocked) →
 * WorkflowExecutionCompleted published → ClaudonyLedgerEventCapture writes ledger →
 * JpaCaseLineageQuery.findCompletedWorkers() returns populated WorkerSummary.
 *
 * Drives the engine via CONTEXT_CHANGED event bus directly, bypassing CaseStartedEventHandler
 * (which requires the Quartz scheduler). This exercises the critical provision path:
 * CaseContextChangedEventHandler.tryProvision() → ClaudonyWorkerProvisioner.
 *
 * CDI-only — no HTTP endpoints exercised, no @TestSecurity (PP-20260513-7c227e).
 *
 * Closes #92 Refs #86
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
                    // Index casehub-engine so all engine CDI beans (CaseHubRuntimeImpl,
                    // CaseContextChangedEventHandler, etc.) are visible to Quarkus.
                    // casehub-engine has no jandex.idx of its own — explicit indexing required.
                    // The NoOp/Empty worker beans are @DefaultBean → yield to Claudony's SPIs.
                    "quarkus.index-dependency.casehub-engine.group-id", "io.casehub",
                    "quarkus.index-dependency.casehub-engine.artifact-id", "casehub-engine",
                    // Re-include TestResearcherCase (excluded in %test profile because CaseHub
                    // injects CaseHubRuntime which is only available when casehub-engine is indexed).
                    // Scheduler-dependent beans are excluded: they require casehub-engine-scheduler-quartz
                    // which brings in Quartz JDBC store (JTA) and conflicts with the test context.
                    "quarkus.arc.exclude-types",
                    "io.casehub.ledger.repository.CaseLedgerEntryRepository,"
                    + "io.casehub.ledger.service.CaseLedgerEventCapture,"
                    + "io.casehub.persistence.memory.InMemoryCaseInstanceRepository,"
                    + "io.casehub.persistence.memory.InMemoryCaseMetaModelRepository,"
                    + "io.casehub.persistence.memory.InMemoryEventLogRepository,"
                    + "io.casehub.testing.WorkResultSubmitter,"
                    // Engine beans that require JobScheduler or WorkerExecutionManager
                    // (only provided by casehub-engine-scheduler-quartz which we cannot add
                    // due to Quartz JTA conflicts in the test context):
                    + "io.casehub.engine.internal.engine.handler.CaseStartedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.CaseStatusChangedHandler,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneActivatedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneCompletedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.WorkerScheduleEventHandler,"
                    + "io.casehub.engine.internal.scheduler.SchedulerService,"
                    + "io.casehub.engine.internal.worker.CasehubWorkloadProvider,"
                    + "io.casehub.engine.internal.orchestration.WorkOrchestrator,"
                    + "io.casehub.engine.internal.engine.handler.SignalReceivedEventHandler,"
                    + "io.casehub.engine.internal.engine.recovery.DefaultWorkerExecutionRecoveryService"
            );
        }
    }

    @Inject TestResearcherCase researcherCase;
    @Inject JpaCaseLineageQuery lineageQuery;
    @Inject CaseInstanceRepository caseInstanceRepository;
    @Inject CaseDefinitionRegistry caseDefinitionRegistry;
    @Inject EventBus eventBus;
    @Inject UserTransaction tx;

    @InjectMock TmuxService tmuxService;
    @InjectMock ClaudonyWorkerContextProvider workerContextProvider;

    @Test
    void contextChanged_engineProvisions_andLineageReturnsCompletedSummary() throws Exception {
        doNothing().when(tmuxService).createSession(anyString(), anyString(), anyString());
        // Stub workerContextProvider to avoid JPA call on Vert.x IO thread.
        // ClaudonyWorkerContextProvider.buildContext() calls JpaCaseLineageQuery which is blocking;
        // when invoked from CaseContextChangedEventHandler (IO thread), it throws
        // BlockingOperationNotAllowedException. This is also a production bug — tracked in #115.
        // The mock returns a minimal WorkerContext.
        when(workerContextProvider.buildContext(any(), any(), any()))
                .thenReturn(new WorkerContext("researcher", null, List.of(), List.of(),
                        PropagationContext.createRoot(), Map.of()));

        // Build a minimal CaseInstance with the researcher case definition.
        // We drive the engine via CONTEXT_CHANGED event bus directly, bypassing
        // CaseStartedEventHandler (requires Quartz scheduler not available in test context).
        UUID caseId = UUID.randomUUID();
        CaseDefinition definition = researcherCase.getDefinition();
        CaseMetaModel model = caseDefinitionRegistry.getCaseMetaModel(definition);

        CaseInstance instance = new CaseInstance();
        instance.setUuid(caseId);
        instance.setCaseMetaModel(model);
        instance.setState(CaseStatus.RUNNING);
        instance.setCaseContext(new CaseContextImpl(Map.of("topic", "test-topic")));

        caseInstanceRepository.save(instance).await().atMost(Duration.ofSeconds(5));

        // Fire CONTEXT_CHANGED — this triggers CaseContextChangedEventHandler.tryProvision()
        // which evaluates ContextChangeTrigger(".topic != null") and calls provision().
        eventBus.publish(
                EventBusAddresses.CONTEXT_CHANGED,
                new CaseContextChangedEvent(instance, instance.getCaseContext().asJsonNode()));

        // Wait for ClaudonyWorkerProvisioner.provision() → tmuxService.createSession()
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        verify(tmuxService, atLeastOnce())
                                .createSession(anyString(), anyString(), anyString()));

        // Drive completion: publish WorkflowExecutionCompleted to the engine event bus.
        // Worker name must match what ClaudonyWorkerProvisioner.provision() returns
        // (the capability name = "researcher").
        Capability cap = new Capability("researcher", "{}", "{}");
        Worker provisioned = new Worker("researcher", List.of(cap), ctx -> Map.of());

        eventBus.publish(
                EventBusAddresses.WORKER_EXECUTION_FINISHED,
                new WorkflowExecutionCompleted(
                        instance, provisioned, UUID.randomUUID().toString(), Map.of()));

        // Wait for ClaudonyLedgerEventCapture (@ObservesAsync) to write the ledger entry.
        // findCompletedWorkers() uses JPA (blocking) — wrap each poll in a UserTransaction so
        // Hibernate's TransactionScopedSession is satisfied even without @TestTransaction.
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    tx.begin();
                    try {
                        assertThat(lineageQuery.findCompletedWorkers(caseId))
                                .as("lineage must contain the completed worker")
                                .hasSize(1);
                    } finally {
                        tx.rollback();
                    }
                });

        tx.begin();
        WorkerSummary summary;
        try {
            summary = lineageQuery.findCompletedWorkers(caseId).get(0);
        } finally {
            tx.rollback();
        }
        assertThat(summary.workerName()).as("workerName").isEqualTo("researcher");
        assertThat(summary.workerId()).as("workerId").isEqualTo("researcher");
        assertThat(summary.startedAt()).as("startedAt").isNotNull();
        assertThat(summary.completedAt()).as("completedAt").isNotNull();
        assertThat(summary.ledgerEntryId()).as("ledgerEntryId").isNotNull();
    }
}
