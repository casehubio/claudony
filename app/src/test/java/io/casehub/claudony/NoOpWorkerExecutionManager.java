package io.casehub.claudony;

import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * No-op WorkerExecutionManager — satisfies CaseContextChangedEventHandler's injection in
 * CasehubEnabledProfile. QuartzWorkerExecutionManager (the real impl) requires
 * casehub-engine-scheduler-quartz on the classpath; tests use this @DefaultBean instead.
 *
 * <p>Safe in CasehubEnabledProfile because TestResearcherCase has no scheduled workers;
 * none of the execution methods are invoked. In profiles where real workers execute,
 * @Alternative @Priority(1) implementations (e.g. QuartzWorkerExecutionManager) would
 * override this @DefaultBean automatically.
 */
@DefaultBean
@ApplicationScoped
class NoOpWorkerExecutionManager implements WorkerExecutionManager {

    @Override
    public Uni<Void> submit(Long eventLogId, CaseInstance instance, Worker worker,
            Capability capability, Map<String, Object> inputData) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> schedulePersistedEvent(EventLog scheduledEventLog) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public int getActiveWorkCount(String workerId) {
        return 0;
    }
}
