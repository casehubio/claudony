package dev.claudony.casehub;

import io.casehub.api.model.WorkerContext;

public interface MeshParticipationStrategy {

    /**
     * Returns the participation level for the given worker.
     *
     * @param workerId the worker identifier
     * @param context  the worker context; may be {@code null} if not yet built
     */
    MeshParticipation strategyFor(String workerId, WorkerContext context);

    enum MeshParticipation {
        /** Register on startup, post STATUS, check messages periodically. */
        ACTIVE,
        /** Do not register; only engage when directly addressed. */
        REACTIVE,
        /** No mesh participation. */
        SILENT
    }
}
