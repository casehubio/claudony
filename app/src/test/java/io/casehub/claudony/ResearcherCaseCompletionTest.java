package io.casehub.claudony;

import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.Worker;
import io.casehub.claudony.casehub.ClaudonyWorkerExecutionManager;
import io.casehub.claudony.casehub.ResearcherCase;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.engine.common.internal.model.CaseInstance;
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
 * E2E proof: ResearcherCase auto-completes when the tmux session exits.
 *
 * Chain: startCase → provision (ClaudonyReactiveWorkerProvisioner) →
 *   manual watch() (WorkOrchestrator excluded) → exit detected →
 *   pendingExitSignals.put → WorkflowExecutionCompleted →
 *   WorkerExecutionCompleted lifecycle event →
 *   ClaudonyLedgerEventCapture.drainExitSignal → CaseHubRuntime.signal →
 *   context.workers.researcher.exited = true → CONTEXT_CHANGED →
 *   goal satisfied → case COMPLETED.
 *
 * Like CasehubEnabledProfile (CaseEngineRoundTripTest) but with three key differences:
 * - SignalReceivedEventHandler is active (processes workers.researcher.exited signal)
 * - CaseStatusChangedHandler is active (updates case state to COMPLETED)
 * - TestResearcherCase excluded; ResearcherCase (production bean) is the CaseHub under test.
 *
 * CDI-only — no HTTP endpoints, no @TestSecurity (PP-20260513-7c227e).
 *
 * Closes #148
 */
@QuarkusTest
@TestProfile(ResearcherCaseCompletionTest.ResearcherCaseCasehubProfile.class)
class ResearcherCaseCompletionTest {

    /**
     * Like CasehubEnabledProfile but with SignalReceivedEventHandler and CaseStatusChangedHandler
     * active (needed to process the workers.researcher.exited signal and transition case to COMPLETED),
     * and with TestResearcherCase excluded so only the production ResearcherCase is registered.
     */
    public static class ResearcherCaseCasehubProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "claudony.casehub.enabled", "true",
                    "claudony.casehub.workers.commands.researcher", "claude",
                    "claudony.casehub.workers.commands.default", "claude",
                    // Suppress ServerStartup.checkTmux() — agent mode skips onStart() entirely
                    "claudony.mode", "agent",
                    "quarkus.index-dependency.casehub-engine.group-id", "io.casehub",
                    "quarkus.index-dependency.casehub-engine.artifact-id", "casehub-engine",
                    // Mirrors CasehubEnabledProfile but:
                    // - SignalReceivedEventHandler NOT excluded (processes workers.researcher.exited)
                    // - CaseStatusChangedHandler NOT excluded (transitions case to COMPLETED)
                    // - ResearcherCase NOT excluded (production CaseHub under test)
                    // - TestResearcherCase IS excluded (prevents dual CaseHub bean registration)
                    "quarkus.arc.exclude-types",
                    "io.casehub.ledger.repository.CaseLedgerEntryRepository,"
                    + "io.casehub.ledger.service.CaseLedgerEventCapture,"
                    + "io.casehub.ledger.service.WorkerDecisionEventCapture,"
                    + "io.casehub.persistence.memory.InMemoryCaseInstanceRepository,"
                    + "io.casehub.persistence.memory.InMemoryCaseMetaModelRepository,"
                    + "io.casehub.persistence.memory.InMemoryEventLogRepository,"
                    + "io.casehub.testing.WorkResultSubmitter,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateApprovedHandler,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateExpiredHandler,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateRejectedHandler,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneActivatedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneCompletedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.WorkerScheduleEventHandler,"
                    + "io.casehub.engine.internal.orchestration.WorkOrchestrator,"
                    + "io.casehub.work.core.strategy.RoundRobinStrategy,"
                    + "io.casehub.claudony.TestResearcherCase"
                    // NOT excluded: SignalReceivedEventHandler, CaseStatusChangedHandler,
                    //               DefaultWorkerExecutionRecoveryService (required by SignalReceivedEventHandler), ResearcherCase
            );
        }
    }

    @Inject ResearcherCase researcherCase;
    @Inject SessionRegistry sessionRegistry;
    @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;
    @Inject ClaudonyWorkerExecutionManager execManager;
    @Inject Event<CaseLifecycleEvent> lifecycleEvents;

    @InjectMock TmuxService tmuxService;

    @Test
    void researcherCase_completesWhenWorkerSessionExits() throws Exception {
        // Start a case with topic in context — triggers provision via CaseStartedEventHandler
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

        // WorkOrchestrator is excluded — start the watcher manually.
        // ClaudonyReactiveWorkerProvisioner registers the session in the registry during provision();
        // findByCaseId() retrieves it so we can pass the correct sessionId/sessionName to watch().
        var session = sessionRegistry.findByCaseId(caseId.toString()).get(0);
        CaseInstance instance = caseInstanceRepository.findByUuid(caseId)
                .await().atMost(Duration.ofSeconds(5));
        var cap = new Capability("researcher", "{}", "{}");
        var worker = new Worker("researcher", List.of(cap), ctx -> Map.of());

        // Fire WorkerExecutionStarted before starting the watcher.
        // engine#390: WorkerExecutionCompleted carries actorId="system" (not the worker name);
        // JpaCaseLineageQuery and drainExitSignal both resolve the worker name from the preceding
        // Started entry by sequence number.
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                caseId, null, "ExecuteWorker", "WorkerExecutionStarted", "ACTIVE",
                "researcher", "WORKER", null)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Stub sessionExists() to return false immediately so the watcher detects exit on first poll.
        // watch() starts a virtual thread that: checks registry.find() → exists (session registered) →
        // calls sessionExists() → false → stores pendingExitSignal → publishes WorkflowExecutionCompleted.
        when(tmuxService.sessionExists(anyString())).thenReturn(false);
        execManager.watch(session.id(), session.name(), instance, worker);

        // Assert case reaches COMPLETED.
        // Full chain: watcher publishes WorkflowExecutionCompleted →
        //   WorkflowExecutionCompletedHandler fires WorkerExecutionCompleted lifecycle event →
        //   ClaudonyLedgerEventCapture.onCaseLifecycleEvent writes ledger + drains exit signal →
        //   CaseHubRuntime.signal(caseId, "workers.researcher.exited", true) →
        //   SignalReceivedEventHandler patches context → CONTEXT_CHANGED →
        //   CaseContextChangedEventHandler evaluates goal: .workers.researcher.exited == true →
        //   GoalReachedEventHandler marks goal reached → CaseStatusChangedHandler sets COMPLETED.
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    CaseInstance updated = caseInstanceRepository.findByUuid(caseId)
                            .await().atMost(Duration.ofSeconds(5));
                    assertThat(updated.getState())
                            .as("case state after researcher exit")
                            .isEqualTo(CaseStatus.COMPLETED);
                });
    }
}
