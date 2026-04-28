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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ClaudonyWorkerContextProvider implements WorkerContextProvider {

    private static final Logger log = Logger.getLogger(ClaudonyWorkerContextProvider.class);

    private final CaseLineageQuery lineageQuery;
    private final CaseChannelProvider channelProvider;
    private final MeshParticipationStrategy strategy;
    private final CaseChannelLayout layout;

    @Inject
    public ClaudonyWorkerContextProvider(CaseLineageQuery lineageQuery,
                                          CaseChannelProvider channelProvider,
                                          CaseHubConfig config) {
        this(lineageQuery, channelProvider,
                selectStrategy(config.meshParticipation()),
                CaseChannelLayout.named(config.channelLayout()));
    }

    ClaudonyWorkerContextProvider(CaseLineageQuery lineageQuery,
                                   CaseChannelProvider channelProvider,
                                   MeshParticipationStrategy strategy,
                                   CaseChannelLayout layout) {
        this.lineageQuery = lineageQuery;
        this.channelProvider = channelProvider;
        this.strategy = strategy;
        this.layout = layout;
    }

    ClaudonyWorkerContextProvider(CaseLineageQuery lineageQuery,
                                   CaseChannelProvider channelProvider,
                                   MeshParticipationStrategy strategy) {
        this(lineageQuery, channelProvider, strategy, new NormativeChannelLayout());
    }

    ClaudonyWorkerContextProvider(CaseLineageQuery lineageQuery,
                                   CaseChannelProvider channelProvider) {
        this(lineageQuery, channelProvider, new ActiveParticipationStrategy());
    }

    @Override
    public WorkerContext buildContext(String workerId, WorkRequest task) {
        MeshParticipationStrategy.MeshParticipation participation =
                strategy.strategyFor(workerId, null);

        var props = new HashMap<String, Object>();
        props.put("meshParticipation", participation.name());

        if (Boolean.TRUE.equals(task.input().get("clean-start"))) {
            props.put("clean-start", true);
            return new WorkerContext(task.capability(), null, null, List.of(),
                    PropagationContext.createRoot(), props);
        }

        String caseIdStr = (String) task.input().get("caseId");
        if (caseIdStr == null || caseIdStr.isBlank()) {
            return new WorkerContext(task.capability(), null, null, List.of(),
                    PropagationContext.createRoot(), props);
        }

        UUID caseId;
        try {
            caseId = UUID.fromString(caseIdStr);
        } catch (IllegalArgumentException e) {
            return new WorkerContext(task.capability(), null, null, List.of(),
                    PropagationContext.createRoot(), props);
        }

        List<WorkerSummary> priorWorkers = lineageQuery.findCompletedWorkers(caseId);

        CaseChannel channel = channelProvider.listChannels(caseId).stream()
                .findFirst()
                .orElse(null);

        List<CaseChannelLayout.ChannelSpec> channelSpecs = layout.channelsFor(caseId, null);
        MeshSystemPromptTemplate.generate(workerId, task.capability(), caseId,
                        channelSpecs, priorWorkers, participation)
                .ifPresent(prompt -> props.put("systemPrompt", prompt));

        return new WorkerContext(task.capability(), caseId, channel, priorWorkers,
                PropagationContext.createRoot(), props);
    }

    private static MeshParticipationStrategy selectStrategy(String name) {
        return switch (name) {
            case "active" -> new ActiveParticipationStrategy();
            case "reactive" -> new ReactiveParticipationStrategy();
            case "silent" -> new SilentParticipationStrategy();
            default -> {
                log.errorf("Unknown mesh-participation '%s' — valid values: active, reactive, silent", name);
                throw new IllegalArgumentException("Unknown mesh participation: " + name);
            }
        };
    }

}
