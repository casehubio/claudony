package dev.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.spi.CaseChannelProvider;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpToolsBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ClaudonyCaseChannelProvider implements CaseChannelProvider {

    private static final String CHANNEL_PREFIX = "case-";
    private static final String QHORUS_NAME_KEY = "qhorus-name";

    private final QhorusMcpTools qhorusMcpTools;

    @Inject
    public ClaudonyCaseChannelProvider(QhorusMcpTools qhorusMcpTools) {
        this.qhorusMcpTools = qhorusMcpTools;
    }

    @Override
    public CaseChannel openChannel(UUID caseId, String purpose) {
        String channelName = CHANNEL_PREFIX + caseId + "/" + purpose;
        QhorusMcpToolsBase.ChannelDetail detail =
                qhorusMcpTools.createChannel(channelName, purpose, null, null);
        return new CaseChannel(
                detail.channelId().toString(),
                detail.name(),
                purpose,
                "qhorus",
                Map.of(QHORUS_NAME_KEY, detail.name()));
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

    private String extractPurpose(String channelName, UUID caseId) {
        String prefix = CHANNEL_PREFIX + caseId + "/";
        return channelName.startsWith(prefix) ? channelName.substring(prefix.length()) : channelName;
    }
}
