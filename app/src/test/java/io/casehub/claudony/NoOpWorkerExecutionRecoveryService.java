package io.casehub.claudony;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/** No-op stub so SignalReceivedEventHandler can inject WorkerExecutionRecoveryService in tests. */
@DefaultBean
@ApplicationScoped
public class NoOpWorkerExecutionRecoveryService implements WorkerExecutionRecoveryService {

    @Override
    public CaseInstance loadOrRestoreCaseInstance(UUID caseId) {
        return null;
    }

    @Override
    public void recoverPendingScheduledWorkers() {
    }
}
