package io.casehub.claudony.server;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.persistence.memory.InMemoryChannelStore;
import io.casehub.qhorus.persistence.memory.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the full delivery chain: gateway.initChannel() fires ChannelInitialisedEvent
 * → ClaudonyChannelBackend.onChannelInitialised() registers backend → gateway.fanOut()
 * → ClaudonyChannelBackend.post() → ChannelEventBus.emit().
 *
 * Uses gateway.fanOut() directly because ReactiveMessageService.dispatch() does not
 * call fanOut() yet (Qhorus#193).
 */
@QuarkusTest
class ChannelBackendDeliveryTest {

    @Inject ChannelGateway gateway;
    @Inject ChannelEventBus eventBus;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    private UUID channelId;
    private String channelName;

    @BeforeEach
    void setUp() {
        channelId = UUID.randomUUID();
        channelName = "case-delivery-" + channelId + "/work";
        // initChannel fires ChannelInitialisedEvent → observer registers ClaudonyChannelBackend
        gateway.initChannel(channelId, new ChannelRef(channelId, channelName));
    }

    @AfterEach
    void tearDown() {
        gateway.deregisterBackend(channelId, ClaudonyChannelBackend.BACKEND_ID);
        messageStore.clear();
        channelStore.clear();
    }

    @Test
    void fanOut_afterInitChannel_callsPost_ticksEventBus() throws InterruptedException {
        var ticks = new CopyOnWriteArrayList<Integer>();
        eventBus.subscribe(channelName).subscribe().with(ticks::add);

        OutboundMessage msg = new OutboundMessage(
                UUID.randomUUID(), "agent:claude", MessageType.STATUS,
                "test message", null, null, ActorType.AGENT, null);

        gateway.fanOut(channelId, channelName, msg);

        // fanOut calls backends on virtual threads — give them time to complete
        Thread.sleep(100);

        assertThat(ticks)
                .as("ChannelEventBus should have been ticked by ClaudonyChannelBackend.post()")
                .isNotEmpty();
    }
}
