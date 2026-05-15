package io.casehub.claudony;

import io.casehub.work.api.WorkloadProvider;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * No-op WorkloadProvider for CaseEngineRoundTripTest.
 *
 * Satisfies the CDI injection in CaseContextChangedEventHandler without requiring
 * casehub-engine-scheduler-quartz (which brings in Quartz JDBC store and JTA conflicts).
 *
 * Always returns 0 active work items — correct for the no-static-workers test path where
 * CaseContextChangedEventHandler bypasses workloadProvider entirely (line 230: workers.isEmpty()).
 */
@ApplicationScoped
class NoOpWorkloadProvider implements WorkloadProvider {

    @Override
    public int getActiveWorkCount(String workerId) {
        return 0;
    }
}
