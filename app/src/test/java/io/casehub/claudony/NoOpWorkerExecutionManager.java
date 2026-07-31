package io.casehub.claudony;

import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

/**
 * No-op WorkerExecutionManager — satisfies CaseContextChangedEventHandler's injection in
 * CasehubEnabledProfile. QuartzWorkerExecutionManager (the real impl) requires
 * casehub-engine-scheduler-quartz on the classpath; tests use this @DefaultBean instead.
 *
 * <p>Safe in CasehubEnabledProfile because TestAgentCase has no scheduled workers;
 * none of the execution methods are invoked. In profiles where real workers execute,
 * @Alternative @Priority(1) implementations (e.g. QuartzWorkerExecutionManager) would
 * override this @DefaultBean automatically.
 */
@DefaultBean
@ApplicationScoped
class NoOpWorkerExecutionManager implements WorkerExecutionManager {

    @Override
    public boolean supports(String capabilityName, String tenancyId) {
        return false;
    }

    @Override
    public void submit(Long eventLogId, CaseInstance instance, Worker worker,
                       Capability capability, Map<String, Object> inputData) {
    }

    @Override
    public void schedulePersistedEvent(EventLog scheduledEventLog) {
    }

    @Override
    public int getActiveWorkCount(String workerId) {
        return 0;
    }
}
