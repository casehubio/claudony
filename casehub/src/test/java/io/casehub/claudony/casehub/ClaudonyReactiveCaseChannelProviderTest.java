package io.casehub.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;
import io.casehub.qhorus.runtime.message.ReactiveMessageService;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudonyReactiveCaseChannelProviderTest {

    private ReactiveChannelService  channelService;
    private ReactiveMessageService  messageService;
    private ClaudonyReactiveCaseChannelProvider provider;

    @BeforeEach
    void setUp() {
        channelService = mock(ReactiveChannelService.class);
        messageService = mock(ReactiveMessageService.class);
        provider = new ClaudonyReactiveCaseChannelProvider(channelService, messageService, new NormativeChannelLayout());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Channel stubChannel(UUID channelId, String name) {
        Channel ch = new Channel();
        ch.id   = channelId;
        ch.name = name;
        return ch;
    }

    /** Stubs channelService.create(...) for any name containing caseId. */
    private void stubCreate(UUID caseId) {
        when(channelService.create(
                contains(caseId.toString()), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                .thenAnswer(inv -> {
                    String name = inv.getArgument(0);
                    return Uni.createFrom().item(stubChannel(UUID.randomUUID(), name));
                });
    }

    // ── openChannel ──────────────────────────────────────────────────────────

    @Test
    void openChannel_createsCaseChannel() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        CaseChannel result = provider.openChannel(caseId, "work").await().indefinitely();

        assertThat(result).isNotNull();
        assertThat(result.purpose()).isEqualTo("work");
        assertThat(result.backendType()).isEqualTo("qhorus");
        assertThat(result.properties()).containsKey("qhorus-name");
    }

    @Test
    void openChannel_initializesAllLayoutChannels() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work").await().indefinitely();

        // NormativeChannelLayout opens 3 channels on first touch
        verify(channelService, times(3)).create(
                anyString(), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void openChannel_cacheHit_doesNotCallChannelService() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work").await().indefinitely();
        provider.openChannel(caseId, "observe").await().indefinitely();

        // Still only 3 createChannel calls total (initialised on first touch)
        verify(channelService, times(3)).create(
                anyString(), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void openChannel_differentCaseIds_initializeSeparately() {
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();
        stubCreate(caseId1);
        stubCreate(caseId2);

        provider.openChannel(caseId1, "work").await().indefinitely();
        provider.openChannel(caseId2, "work").await().indefinitely();

        verify(channelService, times(6)).create(
                anyString(), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void openChannel_channelNameContainsCaseIdAndPurpose() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work").await().indefinitely();

        verify(channelService).create(
                eq("case-" + caseId + "/work"), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void openChannel_oversightChannel_passesAllowedTypes() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "oversight").await().indefinitely();

        verify(channelService).create(
                contains("/oversight"), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq("COMMAND,QUERY"));
    }

    @Test
    void openChannel_passesSemanticToChannelService() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work").await().indefinitely();

        verify(channelService).create(
                contains("/work"), any(),
                argThat(s -> s != null && s.name().equals("APPEND")),
                isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    // ── listChannels ─────────────────────────────────────────────────────────

    @Test
    void listChannels_filtersToCase() {
        UUID caseId = UUID.randomUUID();
        Channel matching = stubChannel(UUID.randomUUID(), "case-" + caseId + "/coord");
        Channel other    = stubChannel(UUID.randomUUID(), "case-" + UUID.randomUUID() + "/coord");
        when(channelService.listAll()).thenReturn(Uni.createFrom().item(List.of(matching, other)));

        List<CaseChannel> result = provider.listChannels(caseId).await().indefinitely();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).contains(caseId.toString());
    }

    @Test
    void listChannels_noMatch_returnsEmpty() {
        when(channelService.listAll()).thenReturn(Uni.createFrom().item(List.of()));

        List<CaseChannel> result = provider.listChannels(UUID.randomUUID()).await().indefinitely();

        assertThat(result).isEmpty();
    }

    // ── postToChannel ─────────────────────────────────────────────────────────

    @Test
    void postToChannel_sendsViaMessageService() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus",
                Map.of("qhorus-name", "case-x/work"));

        when(messageService.send(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().nullItem());

        provider.postToChannel(ch, "alice", "hello", MessageType.STATUS).await().indefinitely();

        verify(messageService).send(
                eq(channelId), eq("alice"), eq(MessageType.STATUS), eq("hello"),
                isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void postToChannel_nullType_sendsWithNullType() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus",
                Map.of("qhorus-name", "case-x/work"));

        when(messageService.send(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().nullItem());

        provider.postToChannel(ch, "alice", "hello", null).await().indefinitely();

        verify(messageService).send(
                eq(channelId), eq("alice"), isNull(), eq("hello"),
                isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void postToChannel_commandWithCorrelationId_passesCorrelationIdToSend() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus",
                Map.of("qhorus-name", "case-x/work"));
        String content = "{\"type\":\"COMMAND\",\"capability\":\"research\","
                + "\"correlationId\":\"42\",\"input\":{}}";
        when(messageService.send(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().nullItem());

        provider.postToChannel(ch, "engine", content, MessageType.COMMAND).await().indefinitely();

        verify(messageService).send(
                eq(channelId), eq("engine"), eq(MessageType.COMMAND), eq(content),
                eq("42"), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void postToChannel_queryWithCorrelationId_passesCorrelationIdToSend() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus",
                Map.of("qhorus-name", "case-x/work"));
        String content = "{\"type\":\"QUERY\",\"correlationId\":\"q-99\",\"input\":{}}";
        when(messageService.send(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().nullItem());

        provider.postToChannel(ch, "engine", content, MessageType.QUERY).await().indefinitely();

        verify(messageService).send(
                eq(channelId), eq("engine"), eq(MessageType.QUERY), eq(content),
                eq("q-99"), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void postToChannel_commandMalformedJson_sendsWithNullCorrelationId() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus",
                Map.of("qhorus-name", "case-x/work"));
        when(messageService.send(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().nullItem());

        // Must not throw; must still deliver the message
        provider.postToChannel(ch, "engine", "not-valid-json", MessageType.COMMAND)
                .await().indefinitely();

        verify(messageService).send(
                eq(channelId), eq("engine"), eq(MessageType.COMMAND), eq("not-valid-json"),
                isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void postToChannel_nonCommandType_doesNotParseCorrelationId() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus",
                Map.of("qhorus-name", "case-x/work"));
        String content = "{\"correlationId\":\"should-be-ignored\"}";
        when(messageService.send(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().nullItem());

        provider.postToChannel(ch, "engine", content, MessageType.STATUS).await().indefinitely();

        verify(messageService).send(
                eq(channelId), eq("engine"), eq(MessageType.STATUS), eq(content),
                isNull(), isNull(), isNull(), isNull(), isNull());
    }

    // ── closeChannel ──────────────────────────────────────────────────────────

    @Test
    void closeChannel_isNoOp() {
        CaseChannel ch = new CaseChannel("ch-id", "channel", "purpose", "qhorus", Map.of("qhorus-name", "ch"));

        Void result = provider.closeChannel(ch).await().indefinitely();

        assertThat(result).isNull();
        verifyNoInteractions(channelService, messageService);
    }
}
