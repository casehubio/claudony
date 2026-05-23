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
    private static final String CHANNEL_PREFIX = "case-";
    private static final String QHORUS_NAME_KEY = "qhorus-name";
    private final ReactiveChannelService channelService;
    private final ReactiveMessageService messageService;
    private final CaseChannelLayout layout;
    private final ConcurrentHashMap<UUID, Map<String, CaseChannel>> caseChannels = new ConcurrentHashMap<>();

    @Inject
    public ClaudonyReactiveCaseChannelProvider(ReactiveChannelService channelService,
            ReactiveMessageService messageService, CaseHubConfig config) {
        this.channelService = channelService;
        this.messageService = messageService;
        try {
            this.layout = CaseChannelLayout.named(config.channelLayout());
        } catch (IllegalArgumentException e) {
            log.errorf("Unknown channel-layout '%s' — valid values: normative, simple", config.channelLayout());
            throw e;
        }
    }

    /** Package-private constructor for tests. */
    ClaudonyReactiveCaseChannelProvider(ReactiveChannelService channelService,
            ReactiveMessageService messageService, CaseChannelLayout layout) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.layout = layout;
    }

    @Override
    public Uni<CaseChannel> openChannel(UUID caseId, String purpose) {
        Map<String, CaseChannel> cached = caseChannels.get(caseId);
        if (cached != null) {
            CaseChannel ch = cached.get(purpose);
            if (ch != null) {
                return Uni.createFrom().item(ch);
            }
            // purpose not in layout — create ad-hoc
            return createQhorusChannel(caseId, purpose, null, null)
                    .invoke(ch2 -> cached.put(purpose, ch2));
        }
        return initializeLayout(caseId)
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
        String prefix = CHANNEL_PREFIX + caseId;
        return channelService.listAll()
                .map(channels -> channels.stream()
                        .filter(ch -> ch.name != null && ch.name.startsWith(prefix))
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

        // Create all channels in sequence, accumulate into a map, then cache
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
        return seed.invoke(channels -> caseChannels.put(caseId, channels));
    }

    private Uni<CaseChannel> createQhorusChannel(UUID caseId, String purpose, String semantic, String allowedTypes) {
        String channelName = CHANNEL_PREFIX + caseId + "/" + purpose;
        io.casehub.qhorus.api.channel.ChannelSemantic channelSemantic =
                semantic != null ? io.casehub.qhorus.api.channel.ChannelSemantic.valueOf(semantic) : null;
        return channelService.create(channelName, purpose, channelSemantic,
                        null, null, null, null, null, allowedTypes)
                .map(detail -> new CaseChannel(
                        detail.id.toString(),
                        detail.name,
                        purpose,
                        "qhorus",
                        Map.of(QHORUS_NAME_KEY, detail.name)));
    }

    private static String toAllowedTypesString(Set<MessageType> types) {
        if (types == null || types.isEmpty()) return null;
        return types.stream().map(MessageType::name).sorted().collect(Collectors.joining(","));
    }

    private String extractPurpose(String channelName, UUID caseId) {
        String prefix = CHANNEL_PREFIX + caseId + "/";
        return channelName.startsWith(prefix) ? channelName.substring(prefix.length()) : channelName;
    }
}
