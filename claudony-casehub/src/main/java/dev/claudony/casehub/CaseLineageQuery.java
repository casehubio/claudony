package dev.claudony.casehub;

import io.casehub.api.model.WorkerSummary;
import java.util.List;
import java.util.UUID;

/** Queries the case ledger for prior worker summaries. Default implementation returns empty. */
public interface CaseLineageQuery {
    List<WorkerSummary> findCompletedWorkers(UUID caseId);
}
