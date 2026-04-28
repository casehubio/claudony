package dev.claudony;

import dev.claudony.casehub.JpaCaseLineageQuery;
import io.casehub.api.model.WorkerSummary;
import io.casehub.engine.internal.event.CaseLifecycleEvent;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CaseEngine event → ledger → lineage round-trip integration test.
 *
 * Verifies the ClaudonyLedgerEventCapture → JpaCaseLineageQuery pipeline:
 *   1. Fire CaseLifecycleEvent(WorkerExecutionStarted) and CaseLifecycleEvent(WorkerExecutionCompleted)
 *   2. ClaudonyLedgerEventCapture (@ObservesAsync) writes CaseLedgerEntry rows asynchronously
 *   3. JpaCaseLineageQuery.findCompletedWorkers() returns a populated WorkerSummary
 *
 * Note: this test bypasses CaseHub.startCase() due to a casehub-engine threading issue
 * (engine event-bus handlers are not @Blocking, so JTA calls from IO thread fail).
 * The full provision → complete → lineage chain is verified at the SPI level here,
 * with provision tested separately in WorkerLifecycleSequenceTest.
 *
 * Upstream issue to file: casehub-engine event-bus handlers need @Blocking for JPA consumers.
 *
 * Refs #92 #86
 */
@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class CaseEngineRoundTripTest {

    @Inject Event<CaseLifecycleEvent> lifecycleEvents;
    @Inject JpaCaseLineageQuery lineageQuery;

    @Test
    @TestTransaction
    void caseLifecycleEvents_writeLedgerEntries_andLineageQueryReturnsCompletedSummary()
            throws Exception {
        UUID caseId = UUID.randomUUID();
        String workerId = UUID.randomUUID().toString();

        // Fire started event — ClaudonyLedgerEventCapture observes @ObservesAsync.
        // fireAsync().join() blocks until ALL async observers have completed and committed.
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                caseId, "StartWorker", "WorkerExecutionStarted",
                "RUNNING", workerId, "researcher"))
                .toCompletableFuture().join();

        // Fire completed event — same: observer commits before join() returns.
        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                caseId, "CompleteWorker", "WorkerExecutionCompleted",
                "COMPLETED", workerId, "researcher"))
                .toCompletableFuture().join();

        // By now both ledger entries are committed. Read on the test thread.
        List<WorkerSummary> workers = lineageQuery.findCompletedWorkers(caseId);
        assertThat(workers).as("lineage must contain the completed worker").hasSize(1);

        WorkerSummary summary = workers.get(0);
        assertThat(summary.workerId()).isEqualTo(workerId);
        assertThat(summary.completedAt()).isNotNull();
        assertThat(summary.ledgerEntryId()).isNotNull();
    }
}
