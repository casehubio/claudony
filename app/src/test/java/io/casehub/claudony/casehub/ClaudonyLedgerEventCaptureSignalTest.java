package io.casehub.claudony.casehub;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.claudony.CaseEngineRoundTripTest;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
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
    ClaudonyWorkerExecutionManager execManager;

    @InjectMock
    CaseHubRuntime runtimeMock;

    @Test
    void workerExecutionCompleted_withPendingSignal_firesContextSignal() throws Exception {
        UUID caseId = UUID.randomUUID();
        when(execManager.drainExitSignal(caseId)).thenReturn("researcher");

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, null, "ExecuteWorker", "WorkerExecutionCompleted",
                        "ACTIVE", "system", "SYSTEM", null))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        verify(runtimeMock).signal(caseId, "workers.researcher.exited", true);
    }

    @Test
    void workerExecutionCompleted_noPendingSignal_doesNotFireSignal() throws Exception {
        UUID caseId = UUID.randomUUID();
        when(execManager.drainExitSignal(caseId)).thenReturn(null);

        lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseId, null, "ExecuteWorker", "WorkerExecutionCompleted",
                        "ACTIVE", "system", "SYSTEM", null))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        verify(runtimeMock, never()).signal(any(), anyString(), any());
    }
}
