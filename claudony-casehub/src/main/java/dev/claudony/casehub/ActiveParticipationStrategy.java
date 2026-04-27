package dev.claudony.casehub;

import io.casehub.api.model.WorkerContext;

public class ActiveParticipationStrategy implements MeshParticipationStrategy {

    @Override
    public MeshParticipation strategyFor(String workerId, WorkerContext context) {
        return MeshParticipation.ACTIVE;
    }
}
