package io.casehub.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.mesh.CaseChannelLayout;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ClaudonyCaseChannelProvider implements CaseChannelProvider {

    private static final Logger log             = Logger.getLogger(ClaudonyCaseChannelProvider.class);
    private static final String QHORUS_NAME_KEY = "qhorus-name";
    private final ChannelService                                channelService;
    private final MessageService                                messageService;
    private final CaseChannelLayout                                     layout;
    private final ConcurrentHashMap<CacheKey, Map<String, CaseChannel>> layoutCache = new ConcurrentHashMap<>();
    private final io.casehub.qhorus.runtime.gateway.ChannelGateway      gateway;
    private final io.casehub.claudony.server.TenantContext              tenantContext;
    @Inject
    public ClaudonyCaseChannelProvider(ChannelService channelService,
                                       MessageService messageService, CaseHubConfig config,
                                       io.casehub.qhorus.runtime.gateway.ChannelGateway gateway,
                                       io.casehub.claudony.server.TenantContext tenantContext) {
        this.channelService = channelService;
        this.messageService = messageService;
        try {
            this.layout = CaseChannelLayout.named(config.channelLayout());
        } catch (IllegalArgumentException e) {
            log.errorf("Unknown channel-layout '%s' — valid values: normative, simple", config.channelLayout());
            throw e;
        }
        this.gateway       = gateway;
        this.tenantContext = tenantContext;
    }

    ClaudonyCaseChannelProvider(ChannelService channelService,
                                MessageService messageService, CaseChannelLayout layout,
                                io.casehub.qhorus.runtime.gateway.ChannelGateway gateway,
                                io.casehub.claudony.server.TenantContext tenantContext) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.layout         = layout;
        this.gateway        = gateway;
        this.tenantContext  = tenantContext;
    }

    @Override
    public CaseChannel openChannel(UUID caseId, String purpose) {
        var                      key = new CacheKey(tenantContext.currentTenantId(), caseId);
        Map<String, CaseChannel> channels;
        try {
            channels = layoutCache.computeIfAbsent(key, k -> initializeLayout(caseId));
        } catch (RuntimeException e) {
            layoutCache.remove(key);
            throw e;
        }
        CaseChannel ch = channels.get(purpose);
        if (ch == null) {
            throw new IllegalArgumentException(
                    "Channel purpose '" + purpose + "' is not defined in the layout for case " + caseId);
        }
        return ch;
    }

    @Override
    public void postToChannel(CaseChannel channel, String from, String content,
                              MessageType type, String correlationId, String deadline, String target) {
        messageService.dispatch(MessageDispatch.builder()
                                               .channelId(UUID.fromString(channel.id()))
                                               .sender(from)
                                               .type(type)
                                               .content(content)
                                               .correlationId(correlationId)
                                               .deadline(deadline != null ? java.time.Instant.parse(deadline) : null)
                                               .target(target)
                                               .actorType(io.casehub.platform.api.identity.ActorType.AGENT)
                                               .build());
    }

    @Override
    public void closeChannel(CaseChannel channel) {
        // Qhorus channels are persistent — no close operation
    }

    @Override
    public List<CaseChannel> listChannels(UUID caseId) {
        String prefix = CaseChannel.CASE_CHANNEL_PREFIX + caseId;
        return channelService.findByNamePrefix(prefix).stream()
                             .map(ch -> new CaseChannel(
                                     ch.id().toString(),
                                     ch.name(),
                                     extractPurpose(ch.name(), caseId),
                                     "qhorus",
                                     Map.of(QHORUS_NAME_KEY, ch.name())))
                             .toList();
    }

    private String extractPurpose(String channelName, UUID caseId) {
        String prefix = CaseChannel.CASE_CHANNEL_PREFIX + caseId + "/";
        return channelName.startsWith(prefix) ? channelName.substring(prefix.length()) : channelName;
    }

    // ── internals ────────────────────────────────────────────────────────────

    private Map<String, CaseChannel> initializeLayout(UUID caseId) {
        List<CaseChannelLayout.ChannelSpec> specs  = layout.channelsFor(caseId, null);
        Map<String, CaseChannel>            result = new ConcurrentHashMap<>();
        for (CaseChannelLayout.ChannelSpec spec : specs) {
            CaseChannel ch = createQhorusChannel(caseId, spec.purpose(), spec.semantic().name(),
                                                 spec.allowedTypes(), spec.deniedTypes());
            result.put(spec.purpose(), ch);
        }
        return result;
    }

    private CaseChannel createQhorusChannel(UUID caseId, String purpose, String semantic,
                                            Set<MessageType> allowedTypes, Set<MessageType> deniedTypes) {
        String channelName = CaseChannel.channelName(caseId, purpose);
        io.casehub.qhorus.api.channel.ChannelSemantic channelSemantic =
                semantic != null ? io.casehub.qhorus.api.channel.ChannelSemantic.valueOf(semantic) : null;
        var request = new ChannelCreateRequest(channelName, purpose, channelSemantic,
                                               null, null, null, null, null,
                                               allowedTypes != null ? allowedTypes : Set.of(),
                                               deniedTypes != null ? deniedTypes : Set.of(),
                                               null, null, null, null, null);
        var detail = channelService.create(request);
        return new CaseChannel(
                detail.id().toString(),
                detail.name(),
                purpose,
                "qhorus",
                Map.of(QHORUS_NAME_KEY, detail.name()));
    }

    private record CacheKey(String tenancyId, UUID caseId) {}
}
