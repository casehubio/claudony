package io.casehub.claudony.casehub;

import io.casehub.api.model.WorkerSummary;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;

/** Queries the case ledger for prior worker summaries. */
public interface CaseLineageQuery {
    Uni<List<WorkerSummary>> findCompletedWorkers(UUID caseId);
}
