package io.casehub.claudony.casehub;

import io.casehub.api.model.WorkerSummary;

import java.util.List;
import java.util.UUID;

public interface CaseLineageQuery {
    List<WorkerSummary> findCompletedWorkers(UUID caseId);
}
