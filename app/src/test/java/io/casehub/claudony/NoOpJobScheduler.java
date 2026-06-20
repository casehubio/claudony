package io.casehub.claudony;

import io.casehub.engine.common.internal.scheduler.JobIdentifier;
import io.casehub.engine.common.internal.scheduler.ScheduledJobRequest;
import io.casehub.engine.common.spi.scheduler.JobScheduler;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * No-op JobScheduler — satisfies SchedulerService's JobScheduler injection in CasehubEnabledProfile.
 * SchedulerService is excluded from the default test profile so this bean is only active there.
 * TestAgentCase has no schedule bindings: registerScheduledTriggers() returns immediately,
 * none of the scheduler methods are ever called.
 */
@DefaultBean
@ApplicationScoped
class NoOpJobScheduler implements JobScheduler {

    @Override
    public Uni<Void> schedule(ScheduledJobRequest request) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> schedule(ScheduledJobRequest.Builder builder) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> cancel(JobIdentifier jobId) {
        return Uni.createFrom().item(false);
    }

    @Override
    public Uni<Integer> cancelGroup(String groupName) {
        return Uni.createFrom().item(0);
    }

    @Override
    public Uni<Boolean> exists(JobIdentifier jobId) {
        return Uni.createFrom().item(false);
    }
}
