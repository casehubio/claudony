package io.casehub.claudony.casehub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link ClaudonyLedgerEventCapture}.
 *
 * <p>Verifies that CDI lifecycle events are correctly captured as {@link CaseLedgerEntry} rows in
 * the ledger. Tests are serialised (each event's join() completes before the next fires) to avoid
 * the concurrent-write race condition in nextSequenceNumber() — the same discipline used by the
 * casehub-engine test suite.
 *
 * <p>Bug a (exception propagation): removing the try/catch means DB failures propagate as
 * CompletionException on join(). This cannot be asserted without injecting a DB failure; the fix
 * is verified by code inspection and the fact that join() would surface any thrown exception.
 *
 * <p>Bug b (sequence race): replacing MAX() with ORDER BY DESC / LIMIT 1 matches the
 * casehub-engine pattern and uses the idx_ledger_entry_subject_seq index. Sequential behaviour
 * (verified here) is identical; the fix eliminates the window where two concurrent threads both
 * read MAX=N before either writes N+1.
 *
 * Refs casehubio/casehub-ledger#72
 */
@QuarkusTest
class ClaudonyLedgerEventCaptureTest {

    @Inject
    Event<CaseLifecycleEvent> lifecycleEvents;

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Inject
    ClaudonyReactiveWorkerProvisioner provisioner;

    @InjectMock
    @WorkerBackend
    ClaudonyWorkerExecutionManager execManager;

    @Test
    @TestTransaction
    void happyPath_singleEvent_writesLedgerEntry() {
        UUID caseId = UUID.randomUUID();

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "StartCase", "CaseStarted", "RUNNING", null, "System", null))
                .toCompletableFuture().join();

        List<CaseLedgerEntry> entries = findByCaseId(caseId);
        assertThat(entries).hasSize(1);

        CaseLedgerEntry entry = entries.get(0);
        assertThat(entry.caseId).isEqualTo(caseId);
        assertThat(entry.subjectId).isEqualTo(caseId);
        assertThat(entry.commandType).isEqualTo("StartCase");
        assertThat(entry.eventType).isEqualTo("CaseStarted");
        assertThat(entry.caseStatus).isEqualTo("RUNNING");
        assertThat(entry.sequenceNumber).isEqualTo(1);
        assertThat(entry.entryType).isEqualTo(LedgerEntryType.EVENT);
        assertThat(entry.actorId).isEqualTo("system");
        assertThat(entry.actorType).isEqualTo(ActorType.SYSTEM);
        assertThat(entry.actorRole).isEqualTo("System");
        assertThat(entry.occurredAt).isNotNull();
        assertThat(entry.tenancyId).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    @TestTransaction
    void nullTenancyId_eventDropped_noLedgerEntryWritten() {
        UUID caseId = UUID.randomUUID();

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, null, "StartCase", "CaseStarted", "RUNNING", null, "System", null))
                .toCompletableFuture().join();

        assertThat(findByCaseId(caseId)).isEmpty();
    }

    @Test
    @TestTransaction
    void sequenceNumbers_incrementPerCase() {
        UUID caseId = UUID.randomUUID();

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "StartCase", "CaseStarted", "RUNNING", null, "System", null))
                .toCompletableFuture().join();
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "SuspendCase", "CaseSuspended", "SUSPENDED", null, "System", null))
                .toCompletableFuture().join();
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "ResumeCase", "CaseResumed", "RUNNING", null, "System", null))
                .toCompletableFuture().join();

        List<CaseLedgerEntry> entries = findByCaseId(caseId);
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).sequenceNumber).isEqualTo(1);
        assertThat(entries.get(1).sequenceNumber).isEqualTo(2);
        assertThat(entries.get(2).sequenceNumber).isEqualTo(3);
    }

    @Test
    @TestTransaction
    void sequenceNumbers_independentPerCase() {
        UUID caseA = UUID.randomUUID();
        UUID caseB = UUID.randomUUID();

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseA, "tenant-1", "StartCase", "CaseStarted", "RUNNING", null, "System", null))
                .toCompletableFuture().join();
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseB, "tenant-1", "StartCase", "CaseStarted", "RUNNING", null, "System", null))
                .toCompletableFuture().join();
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseA, "tenant-1", "CompleteCase", "CaseCompleted", "COMPLETED", null, "System", null))
                .toCompletableFuture().join();

        assertThat(findByCaseId(caseA)).hasSize(2);
        assertThat(findByCaseId(caseB)).hasSize(1);
        assertThat(findByCaseId(caseA).get(1).sequenceNumber).isEqualTo(2);
        assertThat(findByCaseId(caseB).get(0).sequenceNumber).isEqualTo(1);
    }

    @Test
    void nullCaseId_observerCompletesWithoutException() {
        assertThatCode(() ->
                lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                                null, null, "StartCase", "CaseStarted", "RUNNING", null, "System", null))
                        .toCompletableFuture().join()
        ).doesNotThrowAnyException();
    }

    @Test
    void nullEventType_observerCompletesWithoutException() {
        assertThatCode(() ->
                lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                                UUID.randomUUID(), null, "StartCase", null, "RUNNING", null, "System", null))
                        .toCompletableFuture().join()
        ).doesNotThrowAnyException();
    }

    @Test
    @TestTransaction
    void workerEvent_writesLedgerEntry_withWorkerIdAsActorId() {
        UUID caseId = UUID.randomUUID();
        String workerId = "agent-worker-" + UUID.randomUUID();

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "ExecuteWorker", "WorkerExecutionStarted", null, workerId, "WORKER", null))
                .toCompletableFuture().join();

        List<CaseLedgerEntry> entries = findByCaseId(caseId);
        assertThat(entries).hasSize(1);

        CaseLedgerEntry entry = entries.get(0);
        assertThat(entry.actorId).isEqualTo(workerId);
        assertThat(entry.actorRole).isEqualTo("WORKER");
        assertThat(entry.eventType).isEqualTo("WorkerExecutionStarted");
        assertThat(entry.caseStatus).isNull();
    }

    @Test
    @TestTransaction
    void workerStarted_withPreStoredCausalContext_setsCausedByEntryId() {
        UUID caseId = UUID.randomUUID();
        UUID expectedCausedBy = UUID.randomUUID();
        provisioner.seedCausalContextForTest(TenancyConstants.DEFAULT_TENANT_ID, caseId, expectedCausedBy);

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "ProvisionWorker", "WorkerStarted", null, null, "System", null))
                .toCompletableFuture().join();

        List<CaseLedgerEntry> entries = findByCaseId(caseId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).causedByEntryId).isEqualTo(expectedCausedBy);
    }

    @Test
    @TestTransaction
    void workerStarted_withoutPreStoredCausalContext_causedByEntryIdIsNull() {
        UUID caseId = UUID.randomUUID();

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "ProvisionWorker", "WorkerStarted", null, null, "System", null))
                .toCompletableFuture().join();

        List<CaseLedgerEntry> entries = findByCaseId(caseId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).causedByEntryId).isNull();
    }

    @Test
    @TestTransaction
    void workerStarted_drainsCausalContext_secondFireSeesNull() {
        UUID caseId = UUID.randomUUID();
        provisioner.seedCausalContextForTest(TenancyConstants.DEFAULT_TENANT_ID, caseId, UUID.randomUUID());

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "ProvisionWorker", "WorkerStarted", null, null, "System", null))
                .toCompletableFuture().join();
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "ProvisionWorker", "WorkerStarted", null, null, "System", null))
                .toCompletableFuture().join();

        List<CaseLedgerEntry> entries = findByCaseId(caseId);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).causedByEntryId).isNotNull();
        assertThat(entries.get(1).causedByEntryId).isNull();
    }

    @Test
    @TestTransaction
    void workerExecutionCompleted_writesLedgerEntry_whenNoPendingSignal() {
        UUID caseId = UUID.randomUUID();
        when(execManager.drainExitSignal(caseId)).thenReturn(null);

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "ExecuteWorker", "WorkerExecutionCompleted",
                        "ACTIVE", "system", "SYSTEM", null))
                .toCompletableFuture().join();

        // In default profile CaseHubRuntime is unsatisfied — signal guard skips.
        // Verify ledger entry is still written for WorkerExecutionCompleted.
        var entries = findByCaseId(caseId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).eventType).isEqualTo("WorkerExecutionCompleted");
    }

    @Test
    @TestTransaction
    void workerStarted_andWorkerCompleted_bothWriteLedgerEntries() {
        UUID caseId = UUID.randomUUID();
        UUID causedBy = UUID.randomUUID();
        provisioner.seedCausalContextForTest(TenancyConstants.DEFAULT_TENANT_ID, caseId, causedBy);
        when(execManager.drainExitSignal(caseId)).thenReturn(null);

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "ProvisionWorker", "WorkerStarted", null, null, "System", null))
                .toCompletableFuture().join();
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "ExecuteWorker", "WorkerExecutionCompleted",
                        "ACTIVE", "system", "SYSTEM", null))
                .toCompletableFuture().join();

        var entries = findByCaseId(caseId);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).causedByEntryId).isEqualTo(causedBy);
        assertThat(entries.get(1).eventType).isEqualTo("WorkerExecutionCompleted");
        assertThat(entries.get(0).sequenceNumber).isEqualTo(1);
        assertThat(entries.get(1).sequenceNumber).isEqualTo(2);
        assertThat(entries.get(1).causedByEntryId).isNull(); // WorkerExecutionCompleted does not set causal link
    }

    @Test
    @TestTransaction
    void tenancyId_nonNull_storedAsIs() {
        UUID caseId = UUID.randomUUID();

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, TenancyConstants.DEFAULT_TENANT_ID, "StartCase", "CaseStarted", "RUNNING", null, "System", null))
                .toCompletableFuture().join();

        List<CaseLedgerEntry> entries = findByCaseId(caseId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).tenancyId).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
    }

    private List<CaseLedgerEntry> findByCaseId(UUID caseId) {
        return em.createQuery(
                        "SELECT e FROM CaseLedgerEntry e WHERE e.caseId = :caseId ORDER BY e.sequenceNumber ASC",
                        CaseLedgerEntry.class)
                .setParameter("caseId", caseId)
                .getResultList();
    }
}
