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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E proof: when the exit signal fires, the agent case reaches COMPLETED.
 *
 * Tests the core completion chain:
 *   CaseHubRuntime.signal("workers.agent.exited", true) →
 *   SignalReceivedEventHandler patches context → CONTEXT_CHANGED →
 *   CaseContextChangedEventHandler evaluates goal: .workers.agent.exited == true →
 *   GoalReachedEventHandler marks goal reached → CaseStatusChangedHandler sets COMPLETED.
 *
 * Uses TestCompletionCase (no bindings) to eliminate the provision retry timer that would
 * otherwise race with the exit signal via the engine's Vert.x lock in SignalReceivedEventHandler.
 * The watcher→drainExitSignal→signal chain is covered by ClaudonyLedgerEventCaptureTest.
 *
 * CDI-only — no HTTP endpoints, no @TestSecurity (PP-20260513-7c227e).
 */
@QuarkusTest
@TestProfile(AgentCaseCompletionTest.CompletionTestProfile.class)
class AgentCaseCompletionTest {

    /**
     * Enables the engine's goal evaluation and completion handlers.
     * TestCompletionCase is active; AgentCase and TestAgentCase are excluded
     * to prevent duplicate CaseHub bean registration and binding-driven provision retries.
     */
    public static class CompletionTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "claudony.casehub.enabled", "true",
                    "claudony.mode", "agent",
                    "quarkus.index-dependency.casehub-engine.group-id", "io.casehub",
                    "quarkus.index-dependency.casehub-engine.artifact-id", "casehub-engine",
                    "quarkus.index-dependency.neocortex-memory.group-id", "io.casehub",
                    "quarkus.index-dependency.neocortex-memory.artifact-id", "casehub-neocortex-memory",
                    // NOT excluded: SignalReceivedEventHandler, CaseStatusChangedHandler,
                    //               GoalReachedEventHandler, DefaultWorkerExecutionRecoveryService
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
                    + "io.casehub.engine.scheduler.**,"
                    + "io.casehub.engine.trust.**,"
                    + "io.casehub.connectors.**,"
                    + "io.casehub.work.core.**,"
                    // --- runtime-core handlers NOT needed ---
                    + "io.casehub.engine.internal.engine.handler.ActionGateApprovedHandler,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateExpiredHandler,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateRejectedHandler,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneActivatedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneCompletedEventHandler,"
                    + "io.casehub.engine.internal.engine.handler.WorkerScheduleEventHandler,"
                    // --- runtime EventBusAdapters for excluded handlers ---
                    + "io.casehub.engine.internal.engine.handler.ActionGateApprovedEventBusAdapter,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateExpiredEventBusAdapter,"
                    + "io.casehub.engine.internal.engine.handler.ActionGateRejectedEventBusAdapter,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneActivatedEventBusAdapter,"
                    + "io.casehub.engine.internal.engine.handler.MilestoneCompletedEventBusAdapter,"
                    + "io.casehub.engine.internal.engine.handler.WorkerScheduleEventBusAdapter,"
                    // --- claudony / qhorus ---
                    + "io.casehub.claudony.TestAgentCase,"
                    + "io.casehub.claudony.casehub.AgentCase,"
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

    @Inject TestCompletionCase completionCase;
    @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;
    @Inject CaseHubRuntime caseHubRuntime;

    @Test
    void agentCase_completesWhenWorkerSessionExits() throws Exception {
        UUID caseId = completionCase.startCase();

        // Signal exit — triggers goal evaluation → COMPLETED.
        // No sleep needed: TestCompletionCase has no bindings so no competing CONTEXT_CHANGED.
        caseHubRuntime.signal(caseId, "workers.agent.exited", true);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    CaseInstance updated = caseInstanceRepository.findByUuid(caseId);
                    assertThat(updated.getState())
                            .as("case state after agent exit")
                            .isEqualTo(CaseStatus.COMPLETED);
                });
    }
}
