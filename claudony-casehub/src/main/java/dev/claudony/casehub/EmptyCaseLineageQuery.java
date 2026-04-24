package dev.claudony.casehub;

import io.casehub.api.model.WorkerSummary;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/**
 * Default CaseLineageQuery — returns empty list when no JPA-backed implementation is wired.
 * Replace with a CaseLedgerEntryRepository-backed impl once the casehub datasource is configured.
 */
@ApplicationScoped
@DefaultBean
public class EmptyCaseLineageQuery implements CaseLineageQuery {

    @Override
    public List<WorkerSummary> findCompletedWorkers(UUID caseId) {
        return List.of();
    }
}
