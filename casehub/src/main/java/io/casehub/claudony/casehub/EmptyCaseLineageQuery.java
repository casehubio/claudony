package io.casehub.claudony.casehub;

import io.casehub.api.model.WorkerSummary;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@DefaultBean
public class EmptyCaseLineageQuery implements CaseLineageQuery {

    @Override
    public Uni<List<WorkerSummary>> findCompletedWorkers(UUID caseId) {
        return Uni.createFrom().item(List.of());
    }
}
