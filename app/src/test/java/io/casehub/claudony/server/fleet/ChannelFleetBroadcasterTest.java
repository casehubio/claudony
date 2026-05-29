package io.casehub.claudony.server.fleet;

import io.casehub.claudony.server.CaseChannelCreatedEvent;
import io.casehub.claudony.server.ClaudonyChannelBackend;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@QuarkusTest
class ChannelFleetBroadcasterTest {

    @Inject PeerRegistry peerRegistry;
    @Inject ChannelGateway gateway;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;
    @Inject Event<CaseChannelCreatedEvent> channelCreatedEvent;

    @AfterEach
    void cleanup() {
        messageStore.clear();
        channelStore.clear();
        peerRegistry.getAllPeers().stream()
                .filter(p -> p.source() == DiscoverySource.MANUAL)
                .forEach(p -> peerRegistry.removePeer(p.id()));
    }

    @Test
    void onCaseChannelCreated_noPeers_doesNothing() throws InterruptedException {
        UUID channelId = UUID.randomUUID();

        assertThatCode(() -> {
            channelCreatedEvent.fire(
                    new CaseChannelCreatedEvent(channelId, "case-" + channelId + "/work"));
            Thread.sleep(100);
        }).doesNotThrowAnyException();
    }

    @Test
    void onCaseChannelCreated_healthyPeer_syncesChannelToLoopback() throws InterruptedException {
        UUID channelId = UUID.randomUUID();
        String channelName = "case-fleet-" + channelId + "/work";
        int testPort = io.restassured.RestAssured.port;

        peerRegistry.addPeer("loopback-peer", "http://localhost:" + testPort,
                "Loopback Test Peer", DiscoverySource.MANUAL, TerminalMode.DIRECT);

        channelCreatedEvent.fire(new CaseChannelCreatedEvent(channelId, channelName));

        Thread.sleep(500);

        assertThat(gateway.listBackends(channelId))
                .extracting(ChannelGateway.BackendRegistration::backendId)
                .contains(ClaudonyChannelBackend.BACKEND_ID);
    }

    @Test
    void onCaseChannelCreated_peerDown_recordsFailureWithoutCrashing() throws InterruptedException {
        UUID channelId = UUID.randomUUID();
        peerRegistry.addPeer("down-peer", "http://localhost:19999",
                "Down Peer", DiscoverySource.MANUAL, TerminalMode.DIRECT);

        assertThatCode(() -> {
            channelCreatedEvent.fire(
                    new CaseChannelCreatedEvent(channelId, "case-fail/work"));
            Thread.sleep(300);
        }).doesNotThrowAnyException();

        assertThat(peerRegistry.findById("down-peer")).isPresent();
    }
}
