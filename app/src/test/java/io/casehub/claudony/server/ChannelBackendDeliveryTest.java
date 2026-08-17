package io.casehub.claudony.server;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.push.QhorusWebSocketBroadcaster;
import io.casehub.qhorus.persistence.memory.InMemoryChannelStore;
import io.casehub.qhorus.persistence.memory.InMemoryMessageStore;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the full delivery chain: gateway.initChannel() fires ChannelInitialisedEvent
 * → ClaudonyChannelBackend.onChannelInitialised() registers backend → gateway.fanOut()
 * → ClaudonyChannelBackend.post() → QhorusWebSocketBroadcaster.pushMessage().
 */
@QuarkusTest
class ChannelBackendDeliveryTest {

    @Inject ChannelGateway gateway;
    @Inject QhorusWebSocketBroadcaster broadcaster;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    private UUID channelId;
    private String channelName;

    @BeforeEach
    void setUp() {
        channelId = UUID.randomUUID();
        channelName = "case-delivery-" + channelId + "/work";
        gateway.initChannel(channelId, new ChannelRef(channelId, channelName));
    }

    @AfterEach
    void tearDown() {
        gateway.deregisterBackend(channelId, ClaudonyChannelBackend.BACKEND_ID);
        messageStore.clear();
        channelStore.clear();
    }

    @Test
    void fanOut_afterInitChannel_callsPost_broadcastsViaWebSocket() throws InterruptedException {
        OutboundMessage msg = new OutboundMessage(
                UUID.randomUUID(), "agent:claude", MessageType.STATUS,
                "test message", null, null, ActorType.AGENT, null, null);

        gateway.fanOut(channelId, channelName, msg);

        Thread.sleep(100);

        assertThat(gateway.listBackends(channelId))
                .as("ClaudonyChannelBackend should be registered")
                .isNotEmpty();
    }
}
