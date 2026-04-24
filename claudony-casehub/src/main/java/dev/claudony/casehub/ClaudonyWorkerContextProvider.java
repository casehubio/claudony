package dev.claudony.casehub;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.spi.WorkerContextProvider;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;

/**
 * Builds worker context from CaseLedgerEntry lineage.
 * Full implementation in T5 — this stub allows T3/T4/T6 to compile.
 */
@ApplicationScoped
public class ClaudonyWorkerContextProvider implements WorkerContextProvider {

    @Override
    public WorkerContext buildContext(String workerId, WorkRequest task) {
        return new WorkerContext(task.capability(), null, null, List.of(),
                PropagationContext.createRoot(), Map.of());
    }
}
