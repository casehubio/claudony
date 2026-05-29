package io.casehub.claudony.casehub;

import io.casehub.api.model.WorkerSummary;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
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
 * <p>Offloads blocking JPA query to a virtual-thread worker pool via
 * {@code runSubscriptionOn(Infrastructure.getDefaultWorkerPool())}. Self-injection
 * ensures the {@code @Transactional(REQUIRED)} interceptor fires from within the
 * lambda — creates a transaction if none exists.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class JpaCaseLineageQuery implements CaseLineageQuery {

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Inject
    JpaCaseLineageQuery self;

    @Override
    public Uni<List<WorkerSummary>> findCompletedWorkers(UUID caseId) {
        return Uni.createFrom()
                  .item(() -> self.blocking(caseId))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Transactional(TxType.REQUIRED)
    public List<WorkerSummary> blocking(UUID caseId) {
        List<CaseLedgerEntry> completed = em.createQuery(
                        "SELECT e FROM CaseLedgerEntry e " +
                        "WHERE e.caseId = :caseId AND e.eventType = 'WorkerExecutionCompleted' " +
                        "ORDER BY e.occurredAt ASC",
                        CaseLedgerEntry.class)
                .setParameter("caseId", caseId)
                .getResultList();

        return completed.stream()
                .map(e -> {
                    // Since engine#390 the WorkerExecutionCompleted actorId is "system".
                    // Resolve the actual worker name from the WorkerExecutionStarted entry
                    // that immediately precedes this completed entry by sequence number.
                    String workerName = resolveWorkerName(caseId, e.sequenceNumber);
                    Instant startedAt = workerName.equals("system")
                            ? e.occurredAt
                            : findStartedAt(caseId, workerName, e.sequenceNumber);
                    return new WorkerSummary(
                            workerName,
                            workerName,
                            startedAt,
                            e.occurredAt,
                            null,
                            e.id);
                })
                .toList();
    }

    /**
     * Returns the actorId of the WorkerExecutionStarted entry with the highest sequence
     * number below the given completedSequence for this case. Using sequence number
     * rather than timestamp avoids ambiguity when multiple workers run concurrently.
     * Falls back to "system" if no started entry is found.
     */
    private String resolveWorkerName(UUID caseId, int completedSequence) {
        return em.createQuery(
                        "SELECT e.actorId FROM CaseLedgerEntry e " +
                        "WHERE e.caseId = :caseId AND e.eventType = 'WorkerExecutionStarted' " +
                        "AND e.sequenceNumber < :seq " +
                        "ORDER BY e.sequenceNumber DESC",
                        String.class)
                .setParameter("caseId", caseId)
                .setParameter("seq", completedSequence)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse("system");
    }

    private Instant findStartedAt(UUID caseId, String workerName, int completedSequence) {
        return em.createQuery(
                        "SELECT e.occurredAt FROM CaseLedgerEntry e " +
                        "WHERE e.caseId = :caseId AND e.actorId = :workerName " +
                        "AND e.eventType = 'WorkerExecutionStarted' " +
                        "AND e.sequenceNumber < :seq " +
                        "ORDER BY e.sequenceNumber DESC",
                        Instant.class)
                .setParameter("caseId", caseId)
                .setParameter("workerName", workerName)
                .setParameter("seq", completedSequence)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }
}
