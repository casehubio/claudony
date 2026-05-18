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
 * ensures {@code @Transactional} interceptor fires from within the lambda.
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

    @Transactional(TxType.SUPPORTS)
    public List<WorkerSummary> blocking(UUID caseId) {
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
