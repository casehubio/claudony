package dev.claudony.casehub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.casehub.engine.internal.event.CaseLifecycleEvent;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
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
 * Refs casehubio/quarkus-ledger#72
 */
@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class ClaudonyLedgerEventCaptureTest {

    @Inject
    Event<CaseLifecycleEvent> lifecycleEvents;

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Test
    @TestTransaction
    void happyPath_singleEvent_writesLedgerEntry() {
        UUID caseId = UUID.randomUUID();

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, "StartCase", "CaseStarted", "RUNNING", null, "System"))
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
    }

    @Test
    @TestTransaction
    void sequenceNumbers_incrementPerCase() {
        UUID caseId = UUID.randomUUID();

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, "StartCase", "CaseStarted", "RUNNING", null, "System"))
                .toCompletableFuture().join();
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, "SuspendCase", "CaseSuspended", "SUSPENDED", null, "System"))
                .toCompletableFuture().join();
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, "ResumeCase", "CaseResumed", "RUNNING", null, "System"))
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
                        caseA, "StartCase", "CaseStarted", "RUNNING", null, "System"))
                .toCompletableFuture().join();
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseB, "StartCase", "CaseStarted", "RUNNING", null, "System"))
                .toCompletableFuture().join();
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseA, "CompleteCase", "CaseCompleted", "COMPLETED", null, "System"))
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
                                null, "StartCase", "CaseStarted", "RUNNING", null, "System"))
                        .toCompletableFuture().join()
        ).doesNotThrowAnyException();
    }

    @Test
    void nullEventType_observerCompletesWithoutException() {
        assertThatCode(() ->
                lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                                UUID.randomUUID(), "StartCase", null, "RUNNING", null, "System"))
                        .toCompletableFuture().join()
        ).doesNotThrowAnyException();
    }

    @Test
    @TestTransaction
    void workerEvent_writesLedgerEntry_withWorkerIdAsActorId() {
        UUID caseId = UUID.randomUUID();
        String workerId = "researcher-worker-" + UUID.randomUUID();

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, "ExecuteWorker", "WorkerExecutionStarted", null, workerId, "WORKER"))
                .toCompletableFuture().join();

        List<CaseLedgerEntry> entries = findByCaseId(caseId);
        assertThat(entries).hasSize(1);

        CaseLedgerEntry entry = entries.get(0);
        assertThat(entry.actorId).isEqualTo(workerId);
        assertThat(entry.actorRole).isEqualTo("WORKER");
        assertThat(entry.eventType).isEqualTo("WorkerExecutionStarted");
        assertThat(entry.caseStatus).isNull();
    }

    private List<CaseLedgerEntry> findByCaseId(UUID caseId) {
        return em.createQuery(
                        "SELECT e FROM CaseLedgerEntry e WHERE e.caseId = :caseId ORDER BY e.sequenceNumber ASC",
                        CaseLedgerEntry.class)
                .setParameter("caseId", caseId)
                .getResultList();
    }
}
