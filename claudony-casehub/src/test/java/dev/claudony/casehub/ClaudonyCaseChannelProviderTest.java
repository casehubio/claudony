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
        provider = new ClaudonyCaseChannelProvider(qhorusMcpTools, new NormativeChannelLayout());
    }

    private QhorusMcpToolsBase.ChannelDetail channelDetail(UUID channelId, String name) {
        return new QhorusMcpToolsBase.ChannelDetail(
                channelId, name, "description", null, null, 0L, null, false, null, null, null, null);
    }

    private void stubCreateChannel(UUID caseId) {
        when(qhorusMcpTools.createChannel(contains(caseId.toString()), anyString(), anyString(), isNull()))
                .thenAnswer(inv -> channelDetail(UUID.randomUUID(), inv.getArgument(0)));
    }

    @Test
    void openChannel_initializesAllLayoutChannels() {
        UUID caseId = UUID.randomUUID();
        stubCreateChannel(caseId);

        provider.openChannel(caseId, "work");

        // NormativeChannelLayout opens 3 channels on first touch
        verify(qhorusMcpTools, times(3)).createChannel(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    void openChannel_returnsChannelMatchingRequestedPurpose() {
        UUID caseId = UUID.randomUUID();
        stubCreateChannel(caseId);

        CaseChannel ch = provider.openChannel(caseId, "work");

        assertThat(ch).isNotNull();
        assertThat(ch.purpose()).isEqualTo("work");
        assertThat(ch.backendType()).isEqualTo("qhorus");
        assertThat(ch.properties()).containsKey("qhorus-name");
    }

    @Test
    void openChannel_secondCallSameCaseId_hitsCache() {
        UUID caseId = UUID.randomUUID();
        stubCreateChannel(caseId);

        provider.openChannel(caseId, "work");
        provider.openChannel(caseId, "observe");

        // Still only 3 createChannel calls total (initialised on first touch)
        verify(qhorusMcpTools, times(3)).createChannel(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    void openChannel_differentCaseIds_initializeSeparately() {
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();
        stubCreateChannel(caseId1);
        stubCreateChannel(caseId2);

        provider.openChannel(caseId1, "work");
        provider.openChannel(caseId2, "work");

        verify(qhorusMcpTools, times(6)).createChannel(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    void openChannel_purposeNotInLayout_createsAdHocChannel() {
        UUID caseId = UUID.randomUUID();
        stubCreateChannel(caseId);
        // Also stub for ad-hoc (null semantic)
        when(qhorusMcpTools.createChannel(contains(caseId.toString()), anyString(), isNull(), isNull()))
                .thenAnswer(inv -> channelDetail(UUID.randomUUID(), inv.getArgument(0)));

        CaseChannel ch = provider.openChannel(caseId, "custom-purpose");

        assertThat(ch.purpose()).isEqualTo("custom-purpose");
        // 3 layout channels + 1 ad-hoc
        verify(qhorusMcpTools, times(4)).createChannel(anyString(), anyString(), any(), isNull());
    }

    @Test
    void openChannel_channelNameContainsCaseIdAndPurpose() {
        UUID caseId = UUID.randomUUID();
        stubCreateChannel(caseId);

        provider.openChannel(caseId, "work");

        verify(qhorusMcpTools).createChannel(eq("case-" + caseId + "/work"), anyString(), anyString(), isNull());
    }

    @Test
    void openChannel_passesSemanticToQhorus() {
        UUID caseId = UUID.randomUUID();
        stubCreateChannel(caseId);

        provider.openChannel(caseId, "work");

        verify(qhorusMcpTools).createChannel(contains("/work"), anyString(), eq("APPEND"), isNull());
    }

    @Test
    void postToChannel_sendsViaQhorus() {
        UUID caseId = UUID.randomUUID();
        String channelName = "case-" + caseId + "/work";
        CaseChannel ch = new CaseChannel("ch-id", channelName, "work", "qhorus",
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
}
