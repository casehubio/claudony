package io.casehub.claudony.casehub;

import io.casehub.api.model.WorkerSummary;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
@DefaultBean
public class EmptyCaseLineageQuery implements CaseLineageQuery {

    @Override
    public List<WorkerSummary> findCompletedWorkers(UUID caseId) {
        return List.of();
    }
}
