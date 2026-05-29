package io.casehub.claudony.server;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class ClaudonyChannelBackendTest {

    private ChannelEventBus bus;
    private ChannelGateway gateway;
    private ClaudonyChannelBackend backend;

    @BeforeEach
    void setUp() {
        bus = new ChannelEventBus();
        gateway = mock(ChannelGateway.class);
        backend = new ClaudonyChannelBackend(bus, gateway);
    }

    @Test
    void backendId_isStableConstant() {
        assertThat(backend.backendId()).isEqualTo("claudony-observer");
        assertThat(backend.backendId()).isEqualTo(ClaudonyChannelBackend.BACKEND_ID);
    }

    @Test
    void actorType_isHuman() {
        assertThat(backend.actorType()).isEqualTo(ActorType.HUMAN);
    }

    @Test
    void open_doesNotThrow() {
        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "case-123/work");
        assertThatCode(() -> backend.open(ref, Map.of())).doesNotThrowAnyException();
    }

    @Test
    void close_doesNotThrow() {
        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "case-123/work");
        assertThatCode(() -> backend.close(ref)).doesNotThrowAnyException();
    }

    @Test
    void post_ticksChannelEventBus_byChannelName() {
        String channelName = "case-abc/work";
        var received = new CopyOnWriteArrayList<Integer>();
        bus.subscribe(channelName).subscribe().with(received::add);

        ChannelRef ref = new ChannelRef(UUID.randomUUID(), channelName);
        OutboundMessage msg = new OutboundMessage(
                UUID.randomUUID(), "agent:claude", MessageType.STATUS,
                "hello", null, null, ActorType.AGENT);

        backend.post(ref, msg);

        assertThat(received).hasSize(1);
    }

    @Test
    void post_doesNotTickOtherChannels() {
        var otherReceived = new CopyOnWriteArrayList<Integer>();
        bus.subscribe("case-other/work").subscribe().with(otherReceived::add);

        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "case-abc/work");
        backend.post(ref, new OutboundMessage(UUID.randomUUID(), "agent", MessageType.STATUS,
                "msg", null, null, ActorType.AGENT));

        assertThat(otherReceived).isEmpty();
    }

    @Test
    void onChannelInitialised_caseChannel_registersBackend() {
        UUID channelId = UUID.randomUUID();

        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "case-abc/work"));

        verify(gateway).deregisterBackend(channelId, ClaudonyChannelBackend.BACKEND_ID);
        verify(gateway).registerBackend(channelId, backend, "human_observer");
    }

    @Test
    void onChannelInitialised_nonCaseChannel_noRegistration() {
        UUID channelId = UUID.randomUUID();

        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "other-channel/data"));

        verifyNoInteractions(gateway);
    }
}
