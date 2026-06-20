package io.casehub.claudony.casehub;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerSummary;
import io.casehub.api.spi.ReactiveCaseChannelProvider;
import io.casehub.api.spi.ReactiveWorkerContextProvider;
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PostConstruct;
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
    private final Vertx vertx;

    /**
     * Stable pre-created Vert.x event loop context. Created once at @PostConstruct so
     * subsequent calls always emit on the SAME event loop, ensuring the H2 reactive pool
     * is initialised for exactly one event loop.
     *
     * <p>Used with emitOn (not runSubscriptionOn) when buildContext() is called from a
     * non-event-loop thread (e.g. engine's @ConsumeEvent(blocking=true) handler which runs
     * on executeBlocking workers). emitOn shifts WHERE the result is emitted downstream so
     * the engine's post-provision reactive operations run on the event loop. runSubscriptionOn
     * was avoided because it triggers Hibernate Reactive's internal VertxContext.execute()
     * check on worker pool threads, causing HR000068.
     */
    private volatile Context stableEventLoopContext;

    @Inject
    public ClaudonyReactiveWorkerContextProvider(CaseLineageQuery lineageQuery,
                                                  ReactiveCaseChannelProvider channelProvider,
                                                  CaseHubConfig config,
                                                  Vertx vertx) {
        this(lineageQuery, channelProvider,
                selectStrategy(config.meshParticipation()),
                CaseChannelLayout.named(config.channelLayout()),
                vertx);
    }

    ClaudonyReactiveWorkerContextProvider(CaseLineageQuery lineageQuery,
                                           ReactiveCaseChannelProvider channelProvider,
                                           MeshParticipationStrategy strategy,
                                           CaseChannelLayout layout) {
        this(lineageQuery, channelProvider, strategy, layout, null);
    }

    ClaudonyReactiveWorkerContextProvider(CaseLineageQuery lineageQuery,
                                           ReactiveCaseChannelProvider channelProvider,
                                           MeshParticipationStrategy strategy,
                                           CaseChannelLayout layout,
                                           Vertx vertx) {
        this.lineageQuery    = lineageQuery;
        this.channelProvider = channelProvider;
        this.strategy        = strategy;
        this.layout          = layout;
        this.vertx           = vertx;
    }

    ClaudonyReactiveWorkerContextProvider(CaseLineageQuery lineageQuery,
                                           ReactiveCaseChannelProvider channelProvider,
                                           CaseHubConfig config) {
        this(lineageQuery, channelProvider,
                selectStrategy(config.meshParticipation()),
                CaseChannelLayout.named(config.channelLayout()),
                null);
    }

    ClaudonyReactiveWorkerContextProvider(CaseLineageQuery lineageQuery,
                                           ReactiveCaseChannelProvider channelProvider,
                                           MeshParticipationStrategy strategy) {
        this(lineageQuery, channelProvider, strategy, new NormativeChannelLayout(), null);
    }

    ClaudonyReactiveWorkerContextProvider(CaseLineageQuery lineageQuery,
                                           ReactiveCaseChannelProvider channelProvider) {
        this(lineageQuery, channelProvider, new ActiveParticipationStrategy());
    }

    @PostConstruct
    void initStableContext() {
        if (vertx == null) return;
        Context root = vertx.getDelegate().getOrCreateContext();
        stableEventLoopContext = VertxContext.getOrCreateDuplicatedContext(root);
        VertxContextSafetyToggle.setContextSafe(stableEventLoopContext, true);
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

        // findCompletedWorkers() uses runSubscriptionOn(workerPool) + blocking JPA —
        // works from any thread (no assertUseOnEventLoop check).
        // listChannels() handles its own thread safety: returns empty from executor threads
        // (engine's CaseContextChangedEventHandler @ObservesAsync) where channels don't
        // exist yet anyway, and uses the @WithSession reactive path from the event loop.
        Uni<WorkerContext> result = Uni.combine().all()
                .unis(lineageQuery.findCompletedWorkers(caseId),
                        channelProvider.listChannels(caseId))
                .asTuple()
                .map(tuple -> assemble(workerId, caseId, task,
                        tuple.getItem1(), tuple.getItem2(), participation, layout));

        // When called from an executor thread (engine's @ObservesAsync handler), emit the
        // result on the stable event loop context so the engine's post-provision reactive
        // operations (case state update, Hibernate Reactive calls) run on the event loop.
        // emitOn (not runSubscriptionOn) is used deliberately: runSubscriptionOn triggers
        // Hibernate Reactive's internal runSubscriptionOn(workerPool) which strips the
        // Vert.x context, causing HR000068. emitOn only moves WHERE the result is emitted,
        // not WHERE subscription happens — no internal thread switching in Hibernate Reactive.
        if (!io.vertx.core.Context.isOnEventLoopThread() && stableEventLoopContext != null) {
            final Context ctx = stableEventLoopContext;
            return result.emitOn(command -> ctx.runOnContext(v -> command.run()));
        }
        return result;
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
