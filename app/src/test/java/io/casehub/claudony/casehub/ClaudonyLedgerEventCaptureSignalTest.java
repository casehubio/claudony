package io.casehub.claudony.casehub;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.claudony.CaseEngineRoundTripTest;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;

/**
 * Verifies ClaudonyLedgerEventCapture fires a context signal on WorkerExecutionCompleted
 * when CaseHubRuntime is available and execManager returns a pending role.
 *
 * Uses CasehubEnabledProfile so CaseHubRuntimeImpl is in CDI and @InjectMock replaces it.
 */
@QuarkusTest
@TestProfile(CaseEngineRoundTripTest.CasehubEnabledProfile.class)
class ClaudonyLedgerEventCaptureSignalTest {

    @Inject
    Event<CaseLifecycleEvent> lifecycleEvents;

    @InjectMock
    @WorkerBackend
    ClaudonyWorkerExecutionManager execManager;

    @InjectMock
    CaseHubRuntime runtimeMock;

    // Mock ledger repo to isolate signal tests from DB schema requirements.
    // These tests verify signal() is called — ledger persistence is a side effect.
    @InjectMock
    LedgerEntryRepository ledgerRepo;

    @Test
    void workerExecutionCompleted_withPendingSignal_firesContextSignal() throws Exception {
        UUID caseId = UUID.randomUUID();
        when(execManager.drainExitSignal(caseId)).thenReturn("agent");

        lifecycleEvents.fireAsync(CaseLifecycleEvent.of(
                        caseId, "default", "ExecuteWorker", "WorkerExecutionCompleted",
                        "ACTIVE", "system", "SYSTEM", null))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        verify(runtimeMock).signal(caseId, "workers.agent.exited", true);
    }

    @Test
    void workerExecutionCompleted_noPendingSignal_doesNotFireSignal() throws Exception {
        UUID caseId = UUID.randomUUID();
        when(execManager.drainExitSignal(caseId)).thenReturn(null);

        lifecycleEvents.fireAsync(CaseLifecycleEvent.of(
                        caseId, "default", "ExecuteWorker", "WorkerExecutionCompleted",
                        "ACTIVE", "system", "SYSTEM", null))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        verify(runtimeMock, never()).signal(any(), anyString(), any());
    }
}
