package io.casehub.claudony;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseStatus;
import io.casehub.claudony.casehub.TestCompletionCase;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E proof: when the exit signal fires, the researcher case reaches COMPLETED.
 *
 * Tests the core completion chain:
 *   CaseHubRuntime.signal("workers.researcher.exited", true) →
 *   SignalReceivedEventHandler patches context → CONTEXT_CHANGED →
 *   CaseContextChangedEventHandler evaluates goal: .workers.researcher.exited == true →
 *   GoalReachedEventHandler marks goal reached → CaseStatusChangedHandler sets COMPLETED.
 *
 * Uses TestCompletionCase (no bindings) to eliminate the provision retry timer that would
 * otherwise race with the exit signal via the engine's Vert.x lock in SignalReceivedEventHandler.
 * The watcher→drainExitSignal→signal chain is covered by ClaudonyLedgerEventCaptureTest.
 *
 * Known SNAPSHOT instability: GoalReachedEventHandler→CaseStatusChangedHandler chain does not
 * reliably update the case to COMPLETED in the current engine SNAPSHOT. The signal fires and
 * CONTEXT_CHANGED evaluates goals, but CaseStatusChangedHandler write is not visible via
 * CrossTenantCaseInstanceRepository.findByUuid(). Fix tracked in engine; test will pass when
 * the completion chain is stable. See #154.
 *
 * CDI-only — no HTTP endpoints, no @TestSecurity (PP-20260513-7c227e).
 */
@QuarkusTest
@TestProfile(ResearcherCaseCompletionTest.CompletionTestProfile.class)
class ResearcherCaseCompletionTest {

    /**
     * Enables the engine's goal evaluation and completion handlers.
     * TestCompletionCase is active; ResearcherCase and TestResearcherCase are excluded
     * to prevent duplicate CaseHub bean registration and binding-driven provision retries.
     */
    public static class CompletionTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "claudony.casehub.enabled", "true",
                    "claudony.casehub.workers.commands.default", "claude",
                    "claudony.mode", "agent",
                    "quarkus.index-dependency.casehub-engine.group-id", "io.casehub",
                    "quarkus.index-dependency.casehub-engine.artifact-id", "casehub-engine",
                    // NOT excluded: SignalReceivedEventHandler, CaseStatusChangedHandler,
                    //               GoalReachedEventHandler, DefaultWorkerExecutionRecoveryService
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
                    + "io.casehub.engine.internal.orchestration.DefaultWorkOrchestrator,"
                    + "io.casehub.work.core.strategy.RoundRobinStrategy,"
                    + "io.casehub.engine.scheduler.quartz.QuartzWorkerExecutionManager,"
                    + "io.casehub.engine.scheduler.quartz.QuartzWorkerExecutionJob,"
                    + "io.casehub.engine.scheduler.quartz.QuartzWorkerExecutionJobListener,"
                    + "io.casehub.engine.scheduler.quartz.ConditionalScheduledTriggerJob,"
                    + "io.casehub.engine.scheduler.quartz.ScheduledTriggerJob,"
                    + "io.casehub.engine.scheduler.quartz.MilestoneSLATimeoutJob,"
                    + "io.casehub.claudony.TestResearcherCase,"
                    + "io.casehub.claudony.casehub.ResearcherCase"
            );
        }
    }

    @Inject TestCompletionCase completionCase;
    @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;
    @Inject CaseHubRuntime caseHubRuntime;

    @Test
    void researcherCase_completesWhenWorkerSessionExits() throws Exception {
        UUID caseId = completionCase.startCase()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        // Signal exit — triggers goal evaluation → COMPLETED.
        // No sleep needed: TestCompletionCase has no bindings so no competing CONTEXT_CHANGED.
        caseHubRuntime.signal(caseId, "workers.researcher.exited", true);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
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
