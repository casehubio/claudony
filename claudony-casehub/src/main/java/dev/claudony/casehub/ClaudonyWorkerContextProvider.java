package dev.claudony.casehub;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerSummary;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.WorkerContextProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ClaudonyWorkerContextProvider implements WorkerContextProvider {

    private final CaseLineageQuery lineageQuery;
    private final CaseChannelProvider channelProvider;

    @Inject
    public ClaudonyWorkerContextProvider(CaseLineageQuery lineageQuery,
                                          CaseChannelProvider channelProvider) {
        this.lineageQuery = lineageQuery;
        this.channelProvider = channelProvider;
    }

    @Override
    public WorkerContext buildContext(String workerId, WorkRequest task) {
        if (Boolean.TRUE.equals(task.input().get("clean-start"))) {
            return new WorkerContext(task.capability(), null, null, List.of(),
                    PropagationContext.createRoot(), Map.of("clean-start", true));
        }

        String caseIdStr = (String) task.input().get("caseId");
        if (caseIdStr == null || caseIdStr.isBlank()) {
            return new WorkerContext(task.capability(), null, null, List.of(),
                    PropagationContext.createRoot(), Map.of());
        }

        UUID caseId;
        try {
            caseId = UUID.fromString(caseIdStr);
        } catch (IllegalArgumentException e) {
            return new WorkerContext(task.capability(), null, null, List.of(),
                    PropagationContext.createRoot(), Map.of());
        }

        List<WorkerSummary> priorWorkers = lineageQuery.findCompletedWorkers(caseId);

        CaseChannel channel = channelProvider.listChannels(caseId).stream()
                .findFirst()
                .orElse(null);

        return new WorkerContext(task.capability(), caseId, channel, priorWorkers,
                PropagationContext.createRoot(), Map.of());
    }
}
