package dev.claudony.casehub;

import io.casehub.api.model.WorkerSummary;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.quarkiverse.ledger.runtime.persistence.LedgerPersistenceUnit;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA-backed CaseLineageQuery — queries case_ledger_entry for completed worker records.
 *
 * <p>Returns results only when casehub-ledger is in the classpath and CaseHub fires
 * WORKER_EXECUTION_STARTED / WORKER_EXECUTION_COMPLETED CaseLifecycleEvents into the ledger.
 * Until then (pre end-to-end integration), returns empty — same behaviour as EmptyCaseLineageQuery.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class JpaCaseLineageQuery implements CaseLineageQuery {

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Override
    @Transactional(TxType.SUPPORTS)
    public List<WorkerSummary> findCompletedWorkers(UUID caseId) {
        List<CaseLedgerEntry> completed = em.createQuery(
                        "SELECT e FROM CaseLedgerEntry e " +
                        "WHERE e.caseId = :caseId AND e.eventType = 'WorkerExecutionCompleted' " +
                        "ORDER BY e.occurredAt ASC",
                        CaseLedgerEntry.class)
                .setParameter("caseId", caseId)
                .getResultList();

        return completed.stream()
                .map(e -> new WorkerSummary(
                        e.actorId,
                        e.actorId,
                        findStartedAt(caseId, e.actorId, e.occurredAt),
                        e.occurredAt,
                        null,
                        e.id))
                .toList();
    }

    private Instant findStartedAt(UUID caseId, String actorId, Instant before) {
        return em.createQuery(
                        "SELECT e.occurredAt FROM CaseLedgerEntry e " +
                        "WHERE e.caseId = :caseId AND e.actorId = :actorId " +
                        "AND e.eventType = 'WorkerExecutionStarted' AND e.occurredAt <= :before " +
                        "ORDER BY e.occurredAt DESC",
                        Instant.class)
                .setParameter("caseId", caseId)
                .setParameter("actorId", actorId)
                .setParameter("before", before)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(before);
    }
}
