package io.casehub.claudony.casehub;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerSummary;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.WorkerContextProvider;
import io.casehub.api.spi.mesh.ActiveParticipationStrategy;
import io.casehub.api.spi.mesh.CaseChannelLayout;
import io.casehub.api.spi.mesh.MeshParticipationStrategy;
import io.casehub.api.spi.mesh.NormativeChannelLayout;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ClaudonyWorkerContextProvider implements WorkerContextProvider {

    private static final Logger log = Logger.getLogger(ClaudonyWorkerContextProvider.class);

    private final CaseLineageQuery          lineageQuery;
    private final CaseChannelProvider       channelProvider;
    private final MeshParticipationStrategy strategy;
    private final CaseChannelLayout         layout;

    @Inject
    public ClaudonyWorkerContextProvider(CaseLineageQuery lineageQuery,
                                         CaseChannelProvider channelProvider,
                                         CaseHubConfig config) {
        this(lineageQuery, channelProvider,
             MeshParticipationStrategy.named(config.meshParticipation()),
             CaseChannelLayout.named(config.channelLayout()));
    }

    ClaudonyWorkerContextProvider(CaseLineageQuery lineageQuery,
                                  CaseChannelProvider channelProvider,
                                  MeshParticipationStrategy strategy,
                                  CaseChannelLayout layout) {
        this.lineageQuery    = lineageQuery;
        this.channelProvider = channelProvider;
        this.strategy        = strategy;
        this.layout          = layout;
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
    public WorkerContext buildContext(String workerId, UUID caseId, WorkRequest task) {
        MeshParticipationStrategy.MeshParticipation participation = strategy.strategyFor(workerId, caseId);

        if (Boolean.TRUE.equals(task.input().get("clean-start"))) {
            return new WorkerContext(task.capability(), null, List.of(),
                                     List.of(), PropagationContext.createRoot(),
                                     Map.of("meshParticipation", participation.name(), "clean-start", true));
        }

        if (caseId == null) {
            return new WorkerContext(task.capability(), null, List.of(),
                                     List.of(), PropagationContext.createRoot(),
                                     Map.of("meshParticipation", participation.name()));
        }

        List<WorkerSummary> priorWorkers = lineageQuery.findCompletedWorkers(caseId);
        List<CaseChannel>   channels     = channelProvider.listChannels(caseId);

        return assemble(workerId, caseId, task, priorWorkers, channels, participation, layout);
    }

    private static WorkerContext assemble(String workerId,
                                          UUID caseId,
                                          WorkRequest task,
                                          List<WorkerSummary> priorWorkers,
                                          List<CaseChannel> channels,
                                          MeshParticipationStrategy.MeshParticipation participation,
                                          CaseChannelLayout layout) {
        var props = new HashMap<String, Object>();
        props.put("meshParticipation", participation.name());

        List<CaseChannelLayout.ChannelSpec> channelSpecs = layout.channelsFor(caseId, null);
        MeshSystemPromptTemplate.generate(workerId, task.capability(), caseId,
                                          channelSpecs, priorWorkers, participation)
                                .ifPresent(prompt -> props.put("systemPrompt", prompt));

        CaseChannel firstChannel = channels.stream().findFirst().orElse(null);
        return new WorkerContext(task.capability(), caseId,
                                 firstChannel != null ? List.of(firstChannel) : List.of(),
                                 priorWorkers, PropagationContext.createRoot(), props);
    }

}
