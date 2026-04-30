package dev.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ClaudonyCaseChannelProvider implements CaseChannelProvider {

    private static final Logger log = Logger.getLogger(ClaudonyCaseChannelProvider.class);
    private static final String CHANNEL_PREFIX = "case-";
    private static final String QHORUS_NAME_KEY = "qhorus-name";

    private final QhorusMcpTools qhorusMcpTools;
    private final CaseChannelLayout layout;
    private final ConcurrentHashMap<UUID, Map<String, CaseChannel>> caseChannels = new ConcurrentHashMap<>();

    @Inject
    public ClaudonyCaseChannelProvider(QhorusMcpTools qhorusMcpTools, CaseHubConfig config) {
        this.qhorusMcpTools = qhorusMcpTools;
        try {
            this.layout = CaseChannelLayout.named(config.channelLayout());
        } catch (IllegalArgumentException e) {
            log.errorf("Unknown channel-layout '%s' — valid values: normative, simple", config.channelLayout());
            throw e;
        }
    }

    ClaudonyCaseChannelProvider(QhorusMcpTools qhorusMcpTools, CaseChannelLayout layout) {
        this.qhorusMcpTools = qhorusMcpTools;
        this.layout = layout;
    }

    @Override
    public CaseChannel openChannel(UUID caseId, String purpose) {
        Map<String, CaseChannel> channels = caseChannels.computeIfAbsent(caseId, this::initializeLayout);
        return channels.computeIfAbsent(purpose, p -> createQhorusChannel(caseId, p, null));
    }

    @Override
    public void postToChannel(CaseChannel channel, String from, String content) {
        String qhorusName = (String) channel.properties().getOrDefault(QHORUS_NAME_KEY, channel.id());
        qhorusMcpTools.sendMessage(qhorusName, from, "status", content, null, null);
    }

    @Override
    public void closeChannel(CaseChannel channel) {
        // Qhorus channels are persistent — no close operation
    }

    @Override
    public List<CaseChannel> listChannels(UUID caseId) {
        String prefix = CHANNEL_PREFIX + caseId;
        return qhorusMcpTools.listChannels().stream()
                .filter(ch -> ch.name().startsWith(prefix))
                .map(ch -> new CaseChannel(
                        ch.channelId().toString(),
                        ch.name(),
                        extractPurpose(ch.name(), caseId),
                        "qhorus",
                        Map.of(QHORUS_NAME_KEY, ch.name())))
                .toList();
    }

    private Map<String, CaseChannel> initializeLayout(UUID caseId) {
        Map<String, CaseChannel> channels = new ConcurrentHashMap<>();
        for (CaseChannelLayout.ChannelSpec spec : layout.channelsFor(caseId, null)) {
            CaseChannel ch = createQhorusChannel(caseId, spec.purpose(), spec.semantic().name());
            channels.put(spec.purpose(), ch);
        }
        return channels;
    }

    private CaseChannel createQhorusChannel(UUID caseId, String purpose, String semantic) {
        String channelName = CHANNEL_PREFIX + caseId + "/" + purpose;
        QhorusMcpToolsBase.ChannelDetail detail =
                qhorusMcpTools.createChannel(channelName, purpose, semantic, null);
        return new CaseChannel(
                detail.channelId().toString(),
                detail.name(),
                purpose,
                "qhorus",
                Map.of(QHORUS_NAME_KEY, detail.name()));
    }

    private String extractPurpose(String channelName, UUID caseId) {
        String prefix = CHANNEL_PREFIX + caseId + "/";
        return channelName.startsWith(prefix) ? channelName.substring(prefix.length()) : channelName;
    }

}
