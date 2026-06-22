package io.casehub.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.spi.ReactiveCaseChannelProvider;
import io.casehub.api.spi.mesh.CaseChannelLayout;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.casehub.qhorus.runtime.message.ReactiveMessageService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ClaudonyReactiveCaseChannelProvider implements ReactiveCaseChannelProvider {

    private static final Logger log = Logger.getLogger(ClaudonyReactiveCaseChannelProvider.class);
    private static final String QHORUS_NAME_KEY = "qhorus-name";
    private final ReactiveChannelService channelService;
    private final ReactiveMessageService messageService;
    private final CaseChannelLayout layout;
    private final ConcurrentHashMap<UUID, Uni<Map<String, CaseChannel>>> layoutCache = new ConcurrentHashMap<>();
    private final io.casehub.qhorus.runtime.gateway.ChannelGateway gateway;
    private final jakarta.enterprise.event.Event<io.casehub.claudony.server.CaseChannelCreatedEvent> channelCreatedEvent;

    @Inject
    public ClaudonyReactiveCaseChannelProvider(ReactiveChannelService channelService,
            ReactiveMessageService messageService, CaseHubConfig config,
            io.casehub.qhorus.runtime.gateway.ChannelGateway gateway,
            jakarta.enterprise.event.Event<io.casehub.claudony.server.CaseChannelCreatedEvent> channelCreatedEvent) {
        this.channelService = channelService;
        this.messageService = messageService;
        try {
            this.layout = CaseChannelLayout.named(config.channelLayout());
        } catch (IllegalArgumentException e) {
            log.errorf("Unknown channel-layout '%s' — valid values: normative, simple", config.channelLayout());
            throw e;
        }
        this.gateway = gateway;
        this.channelCreatedEvent = channelCreatedEvent;
    }

    /** Package-private constructor for tests (no CDI, no Vert.x injection needed). */
    ClaudonyReactiveCaseChannelProvider(ReactiveChannelService channelService,
            ReactiveMessageService messageService, CaseChannelLayout layout,
            io.casehub.qhorus.runtime.gateway.ChannelGateway gateway,
            jakarta.enterprise.event.Event<io.casehub.claudony.server.CaseChannelCreatedEvent> channelCreatedEvent) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.layout = layout;
        this.gateway = gateway;
        this.channelCreatedEvent = channelCreatedEvent;
    }

    @Override
    public Uni<CaseChannel> openChannel(UUID caseId, String purpose) {
        return layoutCache.computeIfAbsent(caseId,
                        id -> initializeLayout(id)
                                .onFailure().invoke(err -> layoutCache.remove(id))
                                .memoize().indefinitely())
                .map(channels -> {
                    CaseChannel ch = channels.get(purpose);
                    if (ch == null) {
                        throw new IllegalArgumentException(
                            "Channel purpose '" + purpose + "' is not defined in the layout for case " + caseId);
                    }
                    return ch;
                });
    }

    @Override
    public Uni<Void> postToChannel(CaseChannel channel, String from, String content,
            MessageType type, String correlationId, String deadline) {
        return messageService.dispatch(MessageDispatch.builder()
                        .channelId(UUID.fromString(channel.id()))
                        .sender(from)
                        .type(type)
                        .content(content)
                        .correlationId(correlationId)
                        .deadline(deadline != null ? java.time.Instant.parse(deadline) : null)
                        .actorType(io.casehub.platform.api.identity.ActorType.AGENT)
                        .build())
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> closeChannel(CaseChannel channel) {
        // Qhorus channels are persistent — no close operation
        return Uni.createFrom().voidItem();
    }

    /**
     * Returns channels for this case, with thread-safe session handling.
     *
     * <p>Called from two contexts:
     * <ul>
     *   <li><b>Vert.x event loop thread</b> (production reactive paths, tests via
     *       @RunOnVertxContext) — delegates to {@link #doListChannels} which carries
     *       @WithSession("qhorus"). Hibernate Reactive's assertUseOnEventLoop() passes.
     *   <li><b>Non-event-loop thread</b> (engine's @ConsumeEvent(blocking=true) handler
     *       which runs on executeBlocking workers, or JUnit threads) — returns empty.
     *       Semantically correct: channels are created by openChannel() which runs after
     *       provision, so they don't exist at first buildContext() call.
     * </ul>
     *
     * <p>Detection uses {@code Context.isOnEventLoopThread()} rather than
     * {@code Vertx.currentContext().isEventLoopContext()} because executeBlocking workers
     * inherit the event loop Context object (so isEventLoopContext() returns true) but are
     * not the actual event loop thread. @WithSession's runSubscriptionOn uses
     * VertxContext.execute() which asserts the current thread IS the event loop thread,
     * causing HR000068 on executeBlocking workers when called via isEventLoopContext() only.
     */
    @Override
    public Uni<List<CaseChannel>> listChannels(UUID caseId) {
        if (io.vertx.core.Context.isOnEventLoopThread()) {
            return doListChannels(caseId);
        }
        // Not on the Vert.x event loop thread. Covers: executeBlocking workers (which inherit
        // the event loop Context object but run on a different thread), plain executor threads,
        // and JUnit threads. @WithSession's runSubscriptionOn uses VertxContext.execute() which
        // requires the actual event loop thread — isOnEventLoopThread() is the correct check
        // because isEventLoopContext() incorrectly returns true for executeBlocking workers.
        return Uni.createFrom().item(List.of());
    }

    @WithSession("qhorus")
    Uni<List<CaseChannel>> doListChannels(UUID caseId) {
        String prefix = CaseChannel.CASE_CHANNEL_PREFIX + caseId;
        return channelService.findByNamePrefix(prefix)
                .map(channels -> channels.stream()
                        .map(ch -> new CaseChannel(
                                ch.id.toString(),
                                ch.name,
                                extractPurpose(ch.name, caseId),
                                "qhorus",
                                Map.of(QHORUS_NAME_KEY, ch.name)))
                        .toList());
    }

    // ── internals ────────────────────────────────────────────────────────────

    private String extractPurpose(String channelName, UUID caseId) {
        String prefix = CaseChannel.CASE_CHANNEL_PREFIX + caseId + "/";
        return channelName.startsWith(prefix) ? channelName.substring(prefix.length()) : channelName;
    }

    private Uni<Map<String, CaseChannel>> initializeLayout(UUID caseId) {
        List<CaseChannelLayout.ChannelSpec> specs = layout.channelsFor(caseId, null);
        Uni<Map<String, CaseChannel>> seed = Uni.createFrom().item(new ConcurrentHashMap<>());
        for (CaseChannelLayout.ChannelSpec spec : specs) {
            seed = seed.flatMap(acc ->
                    createQhorusChannel(caseId, spec.purpose(), spec.semantic().name(),
                            spec.allowedTypes(), spec.deniedTypes())
                            .map(ch -> {
                                acc.put(spec.purpose(), ch);
                                return acc;
                            }));
        }
        return seed;
    }

    private Uni<CaseChannel> createQhorusChannel(UUID caseId, String purpose, String semantic,
            Set<MessageType> allowedTypes, Set<MessageType> deniedTypes) {
        String channelName = CaseChannel.channelName(caseId, purpose);
        io.casehub.qhorus.api.channel.ChannelSemantic channelSemantic =
                semantic != null ? io.casehub.qhorus.api.channel.ChannelSemantic.valueOf(semantic) : null;
        var request = new ChannelCreateRequest(channelName, purpose, channelSemantic,
                null, null, null, null, null,
                allowedTypes != null ? allowedTypes : Set.of(),
                deniedTypes != null ? deniedTypes : Set.of(),
                null, null, null, null);
        return channelService.create(request)
                .map(detail -> {
                    gateway.initChannel(detail.id,
                            new io.casehub.qhorus.api.gateway.ChannelRef(detail.id, detail.name));
                    channelCreatedEvent.fire(
                            new io.casehub.claudony.server.CaseChannelCreatedEvent(detail.id, detail.name));
                    return new CaseChannel(
                            detail.id.toString(),
                            detail.name,
                            purpose,
                            "qhorus",
                            Map.of(QHORUS_NAME_KEY, detail.name));
                });
    }
}
