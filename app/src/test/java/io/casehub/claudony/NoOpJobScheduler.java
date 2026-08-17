package io.casehub.claudony;

import io.casehub.engine.common.internal.scheduler.JobIdentifier;
import io.casehub.engine.common.internal.scheduler.ScheduledJobRequest;
import io.casehub.engine.common.spi.scheduler.JobScheduler;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
class NoOpJobScheduler implements JobScheduler {

    @Override
    public void schedule(ScheduledJobRequest request) {}

    @Override
    public void schedule(ScheduledJobRequest.Builder builder) {}

    @Override
    public boolean cancel(JobIdentifier jobId) {
        return false;
    }

    @Override
    public int cancelGroup(String groupName) {
        return 0;
    }

    @Override
    public boolean exists(JobIdentifier jobId) {
        return false;
    }
}
