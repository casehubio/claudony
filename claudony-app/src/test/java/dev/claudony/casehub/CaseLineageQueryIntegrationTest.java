package dev.claudony.casehub;

import io.casehub.api.model.WorkerSummary;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for JpaCaseLineageQuery against real Hibernate and the qhorus H2 schema.
 *
 * <p>Verifies the full JPA stack: CaseLedgerEntry entity mapping, JPQL queries,
 * and WorkerSummary result assembly. @TestTransaction rolls back after each test —
 * no manual cleanup needed.
 */
@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class CaseLineageQueryIntegrationTest {

    @Inject JpaCaseLineageQuery lineageQuery;

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Test
    @TestTransaction
    void findsCompletedWorkerFromLedger() {
        final UUID caseId = UUID.randomUUID();
        final String workerId = UUID.randomUUID().toString();
        final Instant startedAt = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        final Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        persist(caseId, workerId, "WORKER_EXECUTION_STARTED", 1, startedAt);
        persist(caseId, workerId, "WORKER_EXECUTION_COMPLETED", 2, completedAt);
        em.flush();

        List<WorkerSummary> result = lineageQuery.findCompletedWorkers(caseId);

        assertThat(result).hasSize(1);
        WorkerSummary s = result.get(0);
        assertThat(s.workerId()).isEqualTo(workerId);
        assertThat(s.workerName()).isEqualTo(workerId);
        assertThat(s.startedAt()).isEqualTo(startedAt);
        assertThat(s.completedAt()).isEqualTo(completedAt);
        assertThat(s.outputSummary()).isNull();
        assertThat(s.ledgerEntryId()).isNotNull();
    }

    @Test
    @TestTransaction
    void returnsEmptyForUnknownCase() {
        assertThat(lineageQuery.findCompletedWorkers(UUID.randomUUID())).isEmpty();
    }

    @Test
    @TestTransaction
    void returnsOnlyCompletedWorkers_notPartiallyStarted() {
        final UUID caseId = UUID.randomUUID();
        final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Worker 1: started AND completed
        persist(caseId, "worker-done", "WORKER_EXECUTION_STARTED", 1, now.minus(5, ChronoUnit.MINUTES));
        persist(caseId, "worker-done", "WORKER_EXECUTION_COMPLETED", 2, now);
        // Worker 2: only started — must NOT appear
        persist(caseId, "worker-running", "WORKER_EXECUTION_STARTED", 3, now.minus(2, ChronoUnit.MINUTES));
        em.flush();

        List<WorkerSummary> result = lineageQuery.findCompletedWorkers(caseId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).workerId()).isEqualTo("worker-done");
    }

    @Test
    @TestTransaction
    void multipleWorkersReturnedInChronologicalOrder() {
        final UUID caseId = UUID.randomUUID();
        final Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        persist(caseId, "worker-first",  "WORKER_EXECUTION_STARTED",   1, base.minus(20, ChronoUnit.MINUTES));
        persist(caseId, "worker-first",  "WORKER_EXECUTION_COMPLETED",  2, base.minus(10, ChronoUnit.MINUTES));
        persist(caseId, "worker-second", "WORKER_EXECUTION_STARTED",    3, base.minus(8, ChronoUnit.MINUTES));
        persist(caseId, "worker-second", "WORKER_EXECUTION_COMPLETED",  4, base);
        em.flush();

        List<WorkerSummary> result = lineageQuery.findCompletedWorkers(caseId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).workerId()).isEqualTo("worker-first");
        assertThat(result.get(1).workerId()).isEqualTo("worker-second");
    }

    @Test
    @TestTransaction
    void startedAtFallsBackToCompletedAtWhenNoStartEntry() {
        final UUID caseId = UUID.randomUUID();
        final Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Only a COMPLETED entry — no corresponding STARTED entry
        persist(caseId, "worker-x", "WORKER_EXECUTION_COMPLETED", 1, completedAt);
        em.flush();

        List<WorkerSummary> result = lineageQuery.findCompletedWorkers(caseId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).startedAt()).isEqualTo(completedAt);
        assertThat(result.get(0).completedAt()).isEqualTo(completedAt);
    }

    private void persist(UUID caseId, String actorId, String eventType, int seq, Instant occurredAt) {
        CaseLedgerEntry e = new CaseLedgerEntry();
        e.id = UUID.randomUUID();
        e.subjectId = caseId;
        e.caseId = caseId;
        e.actorId = actorId;
        e.actorType = ActorType.AGENT;
        e.actorRole = "worker";
        e.eventType = eventType;
        e.entryType = LedgerEntryType.EVENT;
        e.sequenceNumber = seq;
        e.occurredAt = occurredAt;
        em.persist(e);
    }
}
