package io.casehub.claudony;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/** No-op stub so SignalReceivedEventHandler can inject WorkerExecutionRecoveryService in tests. */
@DefaultBean
@ApplicationScoped
public class NoOpWorkerExecutionRecoveryService implements WorkerExecutionRecoveryService {

    @Override
    public Uni<CaseInstance> loadOrRestoreCaseInstance(UUID caseId) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Uni<Void> recoverPendingScheduledWorkers() {
        return Uni.createFrom().voidItem();
    }
}
