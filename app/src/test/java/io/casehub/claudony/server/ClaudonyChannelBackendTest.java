package io.casehub.claudony.server;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.BackendRegistry;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.push.QhorusWebSocketBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ClaudonyChannelBackendTest {

    private QhorusWebSocketBroadcaster broadcaster;
    private BackendRegistry registry;
    private ClaudonyChannelBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        broadcaster = mock(QhorusWebSocketBroadcaster.class);
        registry = mock(BackendRegistry.class);
        backend = new ClaudonyChannelBackend();
        setField(backend, "broadcaster", broadcaster);
        setField(backend, "registry", registry);
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
    void post_broadcastsViaWebSocket() {
        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "case-abc/work");
        OutboundMessage msg = new OutboundMessage(
                UUID.randomUUID(), "agent:claude", MessageType.STATUS,
                "hello", null, null, ActorType.AGENT, null, null);

        backend.post(ref, msg);

        verify(broadcaster).pushMessage(ref, msg);
    }

    @Test
    void post_doesNotBroadcastToOtherChannels() {
        ChannelRef ref = new ChannelRef(UUID.randomUUID(), "case-abc/work");
        OutboundMessage msg = new OutboundMessage(
                UUID.randomUUID(), "agent", MessageType.STATUS,
                "msg", null, null, ActorType.AGENT, null, null);

        backend.post(ref, msg);

        verify(broadcaster).pushMessage(ref, msg);
    }

    @Test
    void onChannelInitialised_registersBackend() {
        UUID channelId = UUID.randomUUID();

        backend.onChannelInitialised(new ChannelInitialisedEvent(channelId, "case-abc/work", false));

        verify(registry).registerBackend(channelId, backend, "human_observer");
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
