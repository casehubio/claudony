package dev.claudony;

import dev.claudony.casehub.JpaCaseLineageQuery;
import dev.claudony.server.TmuxService;
import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerSummary;
import io.casehub.engine.internal.event.EventBusAddresses;
import io.casehub.engine.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.internal.model.CaseInstance;
import io.casehub.engine.spi.CaseInstanceRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

/**
 * Full CaseEngine round-trip integration test.
 *
 * <p>Verifies the complete pipeline:
 * 1. CaseHub.startCase() → engine activates the binding
 * 2. ClaudonyWorkerProvisioner.provision() is called (TmuxService mocked — no real tmux)
 * 3. Completion is driven by publishing WorkflowExecutionCompleted to the event bus
 * 4. CaseLedgerEventCapture writes WorkerExecutionStarted / WorkerExecutionCompleted entries
 * 5. JpaCaseLineageQuery.findCompletedWorkers() returns a populated WorkerSummary
 *
 * <p>Uses CaseHubEnabledProfile to set claudony.casehub.enabled=true and configure the
 * "researcher" capability command so ClaudonyWorkerProvisioner advertises and handles it.
 * casehub-testing in-memory repositories replace JPA persistence for the CaseInstance store.
 *
 * <p>Refs #92 #86
 */
@QuarkusTest
@TestProfile(CaseEngineRoundTripTest.CaseHubEnabledProfile.class)
@TestSecurity(user = "test", roles = "user")
class CaseEngineRoundTripTest {

    public static class CaseHubEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "claudony.casehub.enabled", "true",
                    // Declare "researcher" as a known capability so ClaudonyWorkerProvisioner
                    // advertises it via getCapabilities() and the engine calls provision()
                    "claudony.casehub.workers.commands.researcher", "claude",
                    "claudony.casehub.workers.commands.default", "claude"
            );
        }
    }

    @Inject TestResearcherCase researcherCase;
    @Inject JpaCaseLineageQuery lineageQuery;
    @Inject CaseInstanceRepository caseInstanceRepository;
    @Inject EventBus eventBus;

    @InjectMock TmuxService tmuxService;

    @Test
    void fullRoundTrip_engineCallsProvisioner_andLineageReturnsCompletedSummary() throws Exception {
        // Arrange: mock TmuxService so no real tmux session is created (createSession is void)
        doNothing().when(tmuxService).createSession(anyString(), anyString(), anyString());

        // Act: start case with "topic" key — triggers the ContextChangeTrigger in TestResearcherCase
        UUID caseId = researcherCase.startCase(Map.of("topic", "security-review"))
                .toCompletableFuture()
                .get(15, TimeUnit.SECONDS);

        assertThat(caseId).isNotNull();

        // Wait for the engine to call ClaudonyWorkerProvisioner.provision() and for the
        // WorkerExecutionStarted event to be recorded in the ledger
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    // TmuxService.createSession() should have been called by the provisioner
                    verify(tmuxService, org.mockito.Mockito.atLeastOnce())
                            .createSession(anyString(), anyString(), anyString());
                });

        // Drive completion: retrieve the case instance and publish a WorkflowExecutionCompleted
        // event to the engine's event bus, simulating a real external worker finishing.
        // The Worker name must match what ClaudonyWorkerProvisioner.provision() returns —
        // which is the taskType (= capability name = "researcher").
        CaseInstance instance = caseInstanceRepository.findByUuid(caseId)
                .await().atMost(Duration.ofSeconds(5));

        assertThat(instance).as("case instance must be found after startCase").isNotNull();

        // Construct the provisioned Worker (matches what ClaudonyWorkerProvisioner returns:
        // name = capability name, capabilities = [researcher], function = identity lambda)
        Worker provisionedWorker = new Worker(
                "researcher",
                List.of(new Capability("researcher", ".", ".")),
                ctx -> Map.of("status", "done", "output", "security-report"));

        String idempotency = UUID.randomUUID().toString();
        eventBus.publish(
                EventBusAddresses.WORKER_EXECUTION_FINISHED,
                new WorkflowExecutionCompleted(instance, provisionedWorker, idempotency,
                        Map.of("status", "done", "output", "security-report")));

        // Assert: wait for ledger entries to be written asynchronously by CaseLedgerEventCapture
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<WorkerSummary> workers = lineageQuery.findCompletedWorkers(caseId);
                    assertThat(workers)
                            .as("JpaCaseLineageQuery must return at least one completed WorkerSummary")
                            .isNotEmpty();
                });

        // Verify the WorkerSummary fields
        List<WorkerSummary> workers = lineageQuery.findCompletedWorkers(caseId);
        assertThat(workers).hasSize(1);
        WorkerSummary summary = workers.get(0);
        assertThat(summary.workerName())
                .as("worker name must be 'researcher'")
                .isEqualTo("researcher");
        assertThat(summary.completedAt())
                .as("completedAt must be populated")
                .isNotNull();
        assertThat(summary.ledgerEntryId())
                .as("ledgerEntryId must be populated")
                .isNotNull();
    }
}
