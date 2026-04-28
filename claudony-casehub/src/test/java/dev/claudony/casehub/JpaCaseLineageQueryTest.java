package dev.claudony.casehub;

import io.casehub.api.model.WorkerSummary;
import io.casehub.ledger.model.CaseLedgerEntry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaCaseLineageQueryTest {

    @Mock EntityManager em;
    @Mock TypedQuery<CaseLedgerEntry> entryQuery;
    @Mock TypedQuery<Instant> instantQuery;

    JpaCaseLineageQuery query;

    @BeforeEach
    void setUp() {
        query = new JpaCaseLineageQuery();
        query.em = em;
    }

    @Test
    void returnsEmptyWhenNoLedgerEntries() {
        when(em.createQuery(contains("WorkerExecutionCompleted"), eq(CaseLedgerEntry.class)))
                .thenReturn(entryQuery);
        when(entryQuery.setParameter(anyString(), any())).thenReturn(entryQuery);
        when(entryQuery.getResultList()).thenReturn(List.of());

        List<WorkerSummary> result = query.findCompletedWorkers(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void mapsCompletedEntryToWorkerSummary() {
        UUID caseId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-04-26T10:00:00Z");
        Instant completedAt = Instant.parse("2026-04-26T10:15:00Z");

        CaseLedgerEntry completed = new CaseLedgerEntry();
        completed.id = entryId;
        completed.caseId = caseId;
        completed.actorId = "worker-abc";
        completed.eventType = "WORKER_EXECUTION_COMPLETED";
        completed.occurredAt = completedAt;

        when(em.createQuery(contains("WorkerExecutionCompleted"), eq(CaseLedgerEntry.class)))
                .thenReturn(entryQuery);
        when(entryQuery.setParameter(anyString(), any())).thenReturn(entryQuery);
        when(entryQuery.getResultList()).thenReturn(List.of(completed));

        when(em.createQuery(contains("WorkerExecutionStarted"), eq(Instant.class)))
                .thenReturn(instantQuery);
        when(instantQuery.setParameter(anyString(), any())).thenReturn(instantQuery);
        when(instantQuery.setMaxResults(1)).thenReturn(instantQuery);
        when(instantQuery.getResultStream()).thenReturn(Stream.of(startedAt));

        List<WorkerSummary> result = query.findCompletedWorkers(caseId);

        assertThat(result).hasSize(1);
        WorkerSummary summary = result.get(0);
        assertThat(summary.workerId()).isEqualTo("worker-abc");
        assertThat(summary.workerName()).isEqualTo("worker-abc");
        assertThat(summary.startedAt()).isEqualTo(startedAt);
        assertThat(summary.completedAt()).isEqualTo(completedAt);
        assertThat(summary.outputSummary()).isNull();
        assertThat(summary.ledgerEntryId()).isEqualTo(entryId);
    }

    @Test
    void usesCompletedAtAsStartedAtWhenNoStartEntry() {
        UUID caseId = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-04-26T10:15:00Z");

        CaseLedgerEntry completed = new CaseLedgerEntry();
        completed.id = UUID.randomUUID();
        completed.caseId = caseId;
        completed.actorId = "worker-abc";
        completed.eventType = "WORKER_EXECUTION_COMPLETED";
        completed.occurredAt = completedAt;

        when(em.createQuery(contains("WorkerExecutionCompleted"), eq(CaseLedgerEntry.class)))
                .thenReturn(entryQuery);
        when(entryQuery.setParameter(anyString(), any())).thenReturn(entryQuery);
        when(entryQuery.getResultList()).thenReturn(List.of(completed));

        when(em.createQuery(contains("WorkerExecutionStarted"), eq(Instant.class)))
                .thenReturn(instantQuery);
        when(instantQuery.setParameter(anyString(), any())).thenReturn(instantQuery);
        when(instantQuery.setMaxResults(1)).thenReturn(instantQuery);
        when(instantQuery.getResultStream()).thenReturn(Stream.empty());

        List<WorkerSummary> result = query.findCompletedWorkers(caseId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).startedAt()).isEqualTo(completedAt);
    }
}
