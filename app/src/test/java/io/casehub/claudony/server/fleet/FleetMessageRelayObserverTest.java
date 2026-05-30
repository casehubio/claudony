package io.casehub.claudony.server.fleet;

import io.casehub.claudony.server.ChannelEventBus;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@QuarkusTest
class FleetMessageRelayObserverTest {

    @Inject FleetMessageRelayObserver observer;
    @Inject PeerRegistry peerRegistry;
    @Inject ChannelEventBus eventBus;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    private Cancellable busSubscription;

    @AfterEach
    void cleanup() {
        messageStore.clear();
        channelStore.clear();
        peerRegistry.getAllPeers().stream()
                .filter(p -> p.source() == DiscoverySource.MANUAL)
                .forEach(p -> peerRegistry.removePeer(p.id()));
        if (busSubscription != null) { busSubscription.cancel(); busSubscription = null; }
    }

    @Test
    void onMessage_noPeers_returnsImmediately() {
        assertThatCode(() -> {
            observer.onMessage(new MessageReceivedEvent(
                    "case-no-peers/work", UUID.randomUUID(),
                    MessageType.STATUS, "sender-1", null, "content"));
            Thread.sleep(100);
        }).doesNotThrowAnyException();
    }

    @Test
    void onMessage_healthyPeer_ticksChannelEventBusViaLoopback() throws InterruptedException {
        String channelName = "case-relay-" + UUID.randomUUID() + "/work";
        int testPort = io.restassured.RestAssured.port;

        peerRegistry.addPeer("loopback-peer", "http://localhost:" + testPort,
                "Loopback Test Peer", DiscoverySource.MANUAL, TerminalMode.DIRECT);

        List<Integer> ticks = new CopyOnWriteArrayList<>();
        busSubscription = eventBus.subscribe(channelName).subscribe().with(ticks::add);

        observer.onMessage(new MessageReceivedEvent(
                channelName, UUID.randomUUID(),
                MessageType.STATUS, "sender-1", null, "content"));

        Thread.sleep(500);

        assertThat(ticks).isNotEmpty();
    }

    @Test
    void onMessage_peerDown_recordsFailureWithoutCrashing() throws InterruptedException {
        peerRegistry.addPeer("down-peer", "http://localhost:19999",
                "Down Peer", DiscoverySource.MANUAL, TerminalMode.DIRECT);

        assertThatCode(() -> {
            observer.onMessage(new MessageReceivedEvent(
                    "case-down-test/work", UUID.randomUUID(),
                    MessageType.STATUS, "sender-1", null, "content"));
            Thread.sleep(300);
        }).doesNotThrowAnyException();

        assertThat(peerRegistry.findById("down-peer")).isPresent();
    }
}
