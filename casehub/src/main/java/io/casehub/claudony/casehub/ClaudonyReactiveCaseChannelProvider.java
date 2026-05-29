package io.casehub.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.spi.ReactiveCaseChannelProvider;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;
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

    /** Package-private constructor for tests. */
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

    @Override
    public Uni<List<CaseChannel>> listChannels(UUID caseId) {
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

    private Uni<Map<String, CaseChannel>> initializeLayout(UUID caseId) {
        List<CaseChannelLayout.ChannelSpec> specs = layout.channelsFor(caseId, null);

        Uni<Map<String, CaseChannel>> seed = Uni.createFrom().item(new ConcurrentHashMap<>());
        for (CaseChannelLayout.ChannelSpec spec : specs) {
            String allowedTypes = toAllowedTypesString(spec.allowedTypes());
            seed = seed.flatMap(acc ->
                    createQhorusChannel(caseId, spec.purpose(), spec.semantic().name(), allowedTypes)
                            .map(ch -> {
                                acc.put(spec.purpose(), ch);
                                return acc;
                            }));
        }
        return seed;
    }

    private Uni<CaseChannel> createQhorusChannel(UUID caseId, String purpose, String semantic, String allowedTypes) {
        String channelName = CaseChannel.channelName(caseId, purpose);
        io.casehub.qhorus.api.channel.ChannelSemantic channelSemantic =
                semantic != null ? io.casehub.qhorus.api.channel.ChannelSemantic.valueOf(semantic) : null;
        return channelService.create(channelName, purpose, channelSemantic,
                        null, null, null, null, null, allowedTypes)
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

    private static String toAllowedTypesString(Set<MessageType> types) {
        if (types == null || types.isEmpty()) return null;
        return types.stream().map(MessageType::name).sorted().collect(Collectors.joining(","));
    }

    private String extractPurpose(String channelName, UUID caseId) {
        String prefix = CaseChannel.CASE_CHANNEL_PREFIX + caseId + "/";
        return channelName.startsWith(prefix) ? channelName.substring(prefix.length()) : channelName;
    }
}
