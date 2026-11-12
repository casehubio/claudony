package io.casehub.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.spi.mesh.NormativeChannelLayout;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClaudonyCaseChannelProviderTest {

    private ChannelService                           channelService;
    private MessageService                           messageService;
    private ClaudonyCaseChannelProvider                      provider;
    private io.casehub.qhorus.runtime.gateway.ChannelGateway gateway;

    @BeforeEach
    void setUp() {
        channelService = mock(ChannelService.class);
        messageService = mock(MessageService.class);
        gateway = mock(io.casehub.qhorus.runtime.gateway.ChannelGateway.class);
        provider = new ClaudonyCaseChannelProvider(
                channelService, messageService, new NormativeChannelLayout(),
                gateway,
                () -> io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Channel stubChannel(UUID channelId, String name) {
        return Channel.builder(name)
                .id(channelId)
                .build();
    }

    /** Stubs channelService.create(ChannelCreateRequest) for any name containing caseId. */
    private void stubCreate(UUID caseId) {
        when(channelService.create(argThat((ChannelCreateRequest req) ->
                req != null && req.name().contains(caseId.toString()))))
                .thenAnswer(inv -> {
                    ChannelCreateRequest req = inv.getArgument(0);
                    return (stubChannel(UUID.randomUUID(), req.name()));
                });
    }

    // ── openChannel ──────────────────────────────────────────────────────────

    @Test
    void openChannel_createsCaseChannel() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        CaseChannel result = provider.openChannel(caseId, "work");

        assertThat(result).isNotNull();
        assertThat(result.purpose()).isEqualTo("work");
        assertThat(result.backendType()).isEqualTo("qhorus");
        assertThat(result.properties()).containsKey("qhorus-name");
    }

    @Test
    void openChannel_initializesAllLayoutChannels() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work");

        // NormativeChannelLayout opens 3 channels on first touch
        verify(channelService, times(3)).create(any(ChannelCreateRequest.class));
    }

    @Test
    void openChannel_cacheHit_doesNotCallChannelService() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work");
        provider.openChannel(caseId, "observe");

        // Still only 3 createChannel calls total (initialised on first touch)
        verify(channelService, times(3)).create(any(ChannelCreateRequest.class));
    }

    @Test
    void openChannel_differentCaseIds_initializeSeparately() {
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();
        stubCreate(caseId1);
        stubCreate(caseId2);

        provider.openChannel(caseId1, "work");
        provider.openChannel(caseId2, "work");

        verify(channelService, times(6)).create(any(ChannelCreateRequest.class));
    }

    @Test
    void openChannel_concurrentCallsSameCaseId_initializesOnlyOnce() throws Exception {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        int threads = 3;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        CaseChannel[] results = new CaseChannel[threads];
        Throwable[] errors = new Throwable[threads];
        String[] purposes = {"work", "observe", "work"};

        for (int i = 0; i < threads; i++) {
            int idx = i;
            new Thread(() -> {
                try {
                    ready.countDown();
                    go.await();
                    results[idx] = provider.openChannel(caseId, purposes[idx]);
                    done.countDown();
                } catch (Exception e) {
                    errors[idx] = e;
                    done.countDown();
                }
            }).start();
        }

        ready.await();
        go.countDown();
        done.await();

        for (int i = 0; i < threads; i++) {
            assertThat(errors[i]).as("thread " + i).isNull();
            assertThat(results[i]).as("thread " + i).isNotNull();
            assertThat(results[i].purpose()).isEqualTo(purposes[i]);
        }

        // NormativeChannelLayout has 3 channels — should create exactly 3, not 6 or 9
        verify(channelService, times(3)).create(any(ChannelCreateRequest.class));
    }

    @Test
    void openChannel_failedInit_retriesOnNextCall() {
        UUID caseId = UUID.randomUUID();

        // First call: channelService.create fails
        when(channelService.create(argThat((ChannelCreateRequest req) ->
                req != null && req.name().contains(caseId.toString()))))
                .thenThrow(new RuntimeException("transient error"));

        assertThatThrownBy(() -> provider.openChannel(caseId, "work"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("transient error");

        // Second call: channelService.create succeeds — should retry, not replay cached failure
        stubCreate(caseId);

        CaseChannel result = provider.openChannel(caseId, "work");

        assertThat(result).isNotNull();
        assertThat(result.purpose()).isEqualTo("work");
    }

    @Test
    void openChannel_channelNameContainsCaseIdAndPurpose() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work");

        verify(channelService).create(
                argThat((ChannelCreateRequest req) -> req.name().equals("case-" + caseId + "/work")));
    }

    @Test
    void openChannel_oversightChannel_passesNullAllowedTypesAndDeniedEvent() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "oversight");

        verify(channelService).create(argThat((ChannelCreateRequest req) ->
                req.name().contains("/oversight")
                && req.allowedTypes().isEmpty()
                && req.deniedTypes().equals(Set.of(MessageType.EVENT))));
    }

    @Test
    void openChannel_observeChannel_passesAllowedEventNullDenied() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "observe");

        verify(channelService).create(argThat((ChannelCreateRequest req) ->
                req.name().contains("/observe")
                && req.allowedTypes().equals(Set.of(MessageType.EVENT))
                && req.deniedTypes().isEmpty()));
    }

    @Test
    void openChannel_passesSemanticToChannelService() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work");

        verify(channelService).create(argThat((ChannelCreateRequest req) ->
                req.name().contains("/work")
                && req.semantic() != null && req.semantic().name().equals("APPEND")));
    }

    @Test
    void openChannel_callsInitChannelAfterCreate() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work");

        // NormativeChannelLayout creates 3 channels — initChannel called once per channel
        verify(gateway, times(3)).initChannel(
                any(UUID.class),
                any(io.casehub.qhorus.api.gateway.ChannelRef.class));
    }

    @Test
    void openChannel_initChannelCalledWithCorrectChannelName() {
        UUID caseId = UUID.randomUUID();
        stubCreate(caseId);

        provider.openChannel(caseId, "work");

        verify(gateway).initChannel(
                any(UUID.class),
                argThat(ref -> ref.name().equals("case-" + caseId + "/work")));
    }

    // ── listChannels ─────────────────────────────────────────────────────────

    @Test
    void listChannels_mapsReturnedChannels() {
        UUID caseId = UUID.randomUUID();
        Channel ch = stubChannel(UUID.randomUUID(), "case-" + caseId + "/coord");
        when(channelService.findByNamePrefix("case-" + caseId))
                .thenReturn((List.of(ch)));

        List<CaseChannel> result = provider.listChannels(caseId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("case-" + caseId + "/coord");
        assertThat(result.get(0).purpose()).isEqualTo("coord");
        assertThat(result.get(0).backendType()).isEqualTo("qhorus");
    }

    @Test
    void listChannels_noMatch_returnsEmpty() {
        when(channelService.findByNamePrefix(anyString()))
                .thenReturn((List.of()));

        List<CaseChannel> result = provider.listChannels(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    // ── postToChannel ─────────────────────────────────────────────────────────

    private DispatchResult dr(UUID channelId, String sender, MessageType type) {
        return new DispatchResult(1L, channelId, sender, type, null, null, List.of(), null, null, null, null, 0, List.of());
    }

    @Test
    void postToChannel_sendsViaMessageService() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus",
                Map.of("qhorus-name", "case-x/work"));
        when(messageService.dispatch(any(MessageDispatch.class)))
                .thenReturn((dr(channelId, "alice", MessageType.STATUS)));

        provider.postToChannel(ch, "alice", "hello", MessageType.STATUS, null, null, null);

        verify(messageService).dispatch(argThat(d ->
                channelId.equals(d.channelId()) &&
                "alice".equals(d.sender()) &&
                d.type() == MessageType.STATUS &&
                "hello".equals(d.content()) &&
                d.correlationId() == null &&
                d.deadline() == null &&
                d.actorType() == ActorType.AGENT));
    }

    @Test
    void postToChannel_nullType_throwsIllegalArgumentException() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus",
                Map.of("qhorus-name", "case-x/work"));

        // null type is now rejected by MessageDispatch.builder().build() — null was a silent pass-through
        // with the old send() flat-param API; the builder enforces the 9-type taxonomy invariant.
        assertThatThrownBy(() ->
                provider.postToChannel(ch, "alice", "hello", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type is required");
    }

    @Test
    void postToChannel_commandWithCorrelationId_passesCorrelationIdToDispatch() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus",
                Map.of("qhorus-name", "case-x/work"));
        String content = "{\"type\":\"COMMAND\",\"capability\":\"research\","
                + "\"correlationId\":\"42\",\"input\":{}}";
        when(messageService.dispatch(any(MessageDispatch.class)))
                .thenReturn((dr(channelId, "engine", MessageType.COMMAND)));

        provider.postToChannel(ch, "engine", content, MessageType.COMMAND, "42", null, null);

        verify(messageService).dispatch(argThat(d ->
                channelId.equals(d.channelId()) &&
                "engine".equals(d.sender()) &&
                d.type() == MessageType.COMMAND &&
                "42".equals(d.correlationId()) &&
                d.deadline() == null));
    }

    @Test
    void postToChannel_queryWithCorrelationId_passesCorrelationIdToDispatch() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus",
                Map.of("qhorus-name", "case-x/work"));
        String content = "{\"type\":\"QUERY\",\"correlationId\":\"q-99\",\"input\":{}}";
        when(messageService.dispatch(any(MessageDispatch.class)))
                .thenReturn((dr(channelId, "engine", MessageType.QUERY)));

        provider.postToChannel(ch, "engine", content, MessageType.QUERY, "q-99", null, null);

        verify(messageService).dispatch(argThat(d ->
                channelId.equals(d.channelId()) &&
                "q-99".equals(d.correlationId())));
    }

    @Test
    void postToChannel_withDeadline_passesDeadlineToDispatch() {
        UUID channelId = UUID.randomUUID();
        CaseChannel ch = new CaseChannel(channelId.toString(), "case-x/work", "work", "qhorus",
                Map.of("qhorus-name", "case-x/work"));
        String deadline = "2026-05-23T12:00:00Z";
        when(messageService.dispatch(any(MessageDispatch.class)))
                .thenReturn((dr(channelId, "engine", MessageType.COMMAND)));

        provider.postToChannel(ch, "engine", "{}", MessageType.COMMAND, "42", deadline, null);

        verify(messageService).dispatch(argThat(d ->
                "42".equals(d.correlationId()) &&
                java.time.Instant.parse(deadline).equals(d.deadline())));
    }

    // ── closeChannel ──────────────────────────────────────────────────────────

    @Test
    void closeChannel_isNoOp() {
        CaseChannel ch = new CaseChannel("ch-id", "channel", "purpose", "qhorus", Map.of("qhorus-name", "ch"));

        provider.closeChannel(ch);

        verifyNoInteractions(channelService, messageService);
    }
}
