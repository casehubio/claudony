package dev.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpToolsBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudonyCaseChannelProviderTest {

    private QhorusMcpTools qhorusMcpTools;
    private ClaudonyCaseChannelProvider provider;

    @BeforeEach
    void setUp() {
        qhorusMcpTools = mock(QhorusMcpTools.class);
        provider = new ClaudonyCaseChannelProvider(qhorusMcpTools);
    }

    @Test
    void openChannel_createsQhorusChannelAndReturnsCaseChannel() {
        UUID caseId = UUID.randomUUID();
        var channelDetail = channelDetail(UUID.randomUUID(), "case-" + caseId + "/coordination");
        when(qhorusMcpTools.createChannel(anyString(), anyString(), isNull(), isNull()))
                .thenReturn(channelDetail);

        CaseChannel ch = provider.openChannel(caseId, "coordination");

        assertThat(ch).isNotNull();
        assertThat(ch.backendType()).isEqualTo("qhorus");
        assertThat(ch.purpose()).isEqualTo("coordination");
        assertThat(ch.properties()).containsKey("qhorus-name");
    }

    @Test
    void openChannel_channelNameContainsCaseId() {
        UUID caseId = UUID.randomUUID();
        var channelDetail = channelDetail(UUID.randomUUID(), "case-" + caseId + "/coordination");
        when(qhorusMcpTools.createChannel(anyString(), anyString(), isNull(), isNull()))
                .thenReturn(channelDetail);

        CaseChannel ch = provider.openChannel(caseId, "coordination");

        assertThat(ch.name()).contains(caseId.toString());
    }

    @Test
    void postToChannel_sendsViaQhorus() {
        UUID caseId = UUID.randomUUID();
        String channelName = "case-" + caseId + "/coordination";
        CaseChannel ch = new CaseChannel("ch-id", channelName, "coordination", "qhorus",
                Map.of("qhorus-name", channelName));

        provider.postToChannel(ch, "alice", "hello");

        verify(qhorusMcpTools).sendMessage(eq(channelName), eq("alice"), anyString(),
                eq("hello"), isNull(), isNull());
    }

    @Test
    void closeChannel_isNoOp() {
        CaseChannel ch = new CaseChannel("ch-id", "channel", "purpose", "qhorus", Map.of("qhorus-name", "ch"));
        assertThatNoException().isThrownBy(() -> provider.closeChannel(ch));
        verifyNoInteractions(qhorusMcpTools);
    }

    @Test
    void listChannels_returnsChannelsFilteredByCaseId() {
        UUID caseId = UUID.randomUUID();
        var matching = channelDetail(UUID.randomUUID(), "case-" + caseId + "/coord");
        var other = channelDetail(UUID.randomUUID(), "case-" + UUID.randomUUID() + "/coord");
        when(qhorusMcpTools.listChannels()).thenReturn(List.of(matching, other));

        List<CaseChannel> result = provider.listChannels(caseId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).contains(caseId.toString());
    }

    @Test
    void listChannels_noMatchingChannels_returnsEmpty() {
        when(qhorusMcpTools.listChannels()).thenReturn(List.of());
        assertThat(provider.listChannels(UUID.randomUUID())).isEmpty();
    }

    @Test
    void postToChannel_missingQhorusName_fallsBackToChannelId() {
        CaseChannel ch = new CaseChannel("ch-id", "channel", "purpose", "qhorus", Map.of());
        assertThatNoException().isThrownBy(() -> provider.postToChannel(ch, "alice", "hello"));
        verify(qhorusMcpTools).sendMessage(eq("ch-id"), eq("alice"), anyString(),
                eq("hello"), isNull(), isNull());
    }

    private QhorusMcpToolsBase.ChannelDetail channelDetail(UUID channelId, String name) {
        return new QhorusMcpToolsBase.ChannelDetail(
                channelId, name, "description", null, null, 0L, null, false, null, null, null, null);
    }
}
