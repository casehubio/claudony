package io.casehub.claudony;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

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
    public int getActiveWorkCount(String workerId) {
        return 0;
    }
}
