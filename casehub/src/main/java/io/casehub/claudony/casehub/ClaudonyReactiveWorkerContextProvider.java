package io.casehub.claudony.casehub;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerSummary;
import io.casehub.api.spi.ReactiveCaseChannelProvider;
import io.casehub.api.spi.ReactiveWorkerContextProvider;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ClaudonyReactiveWorkerContextProvider implements ReactiveWorkerContextProvider {

    private static final Logger log = Logger.getLogger(ClaudonyReactiveWorkerContextProvider.class);

    private final CaseLineageQuery lineageQuery;
    private final ReactiveCaseChannelProvider channelProvider;
    private final MeshParticipationStrategy strategy;
    private final CaseChannelLayout layout;

    @Inject
    public ClaudonyReactiveWorkerContextProvider(CaseLineageQuery lineageQuery,
                                                  ReactiveCaseChannelProvider channelProvider,
                                                  CaseHubConfig config) {
        this(lineageQuery, channelProvider,
                selectStrategy(config.meshParticipation()),
                CaseChannelLayout.named(config.channelLayout()));
    }

    ClaudonyReactiveWorkerContextProvider(CaseLineageQuery lineageQuery,
                                           ReactiveCaseChannelProvider channelProvider,
                                           MeshParticipationStrategy strategy,
                                           CaseChannelLayout layout) {
        this.lineageQuery    = lineageQuery;
        this.channelProvider = channelProvider;
        this.strategy        = strategy;
        this.layout          = layout;
    }

    ClaudonyReactiveWorkerContextProvider(CaseLineageQuery lineageQuery,
                                           ReactiveCaseChannelProvider channelProvider,
                                           MeshParticipationStrategy strategy) {
        this(lineageQuery, channelProvider, strategy, new NormativeChannelLayout());
    }

    ClaudonyReactiveWorkerContextProvider(CaseLineageQuery lineageQuery,
                                           ReactiveCaseChannelProvider channelProvider) {
        this(lineageQuery, channelProvider, new ActiveParticipationStrategy());
    }

    @Override
    public Uni<WorkerContext> buildContext(String workerId, UUID caseId, WorkRequest task) {
        MeshParticipationStrategy.MeshParticipation participation = strategy.strategyFor(workerId, null);

        if (Boolean.TRUE.equals(task.input().get("clean-start"))) {
            return Uni.createFrom().item(new WorkerContext(task.capability(), null, List.of(),
                    List.of(), PropagationContext.createRoot(),
                    Map.of("meshParticipation", participation.name(), "clean-start", true)));
        }

        if (caseId == null) {
            return Uni.createFrom().item(new WorkerContext(task.capability(), null, List.of(),
                    List.of(), PropagationContext.createRoot(),
                    Map.of("meshParticipation", participation.name())));
        }

        return Uni.combine().all()
                .unis(lineageQuery.findCompletedWorkers(caseId),
                        channelProvider.listChannels(caseId))
                .asTuple()
                .map(tuple -> assemble(workerId, caseId, task,
                        tuple.getItem1(), tuple.getItem2(), participation, layout));
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

    private static MeshParticipationStrategy selectStrategy(String name) {
        return switch (name) {
            case "active"   -> new ActiveParticipationStrategy();
            case "reactive" -> new ReactiveParticipationStrategy();
            case "silent"   -> new SilentParticipationStrategy();
            default -> {
                log.errorf("Unknown mesh-participation '%s' — valid values: active, reactive, silent", name);
                throw new IllegalArgumentException("Unknown mesh participation: " + name);
            }
        };
    }
}
