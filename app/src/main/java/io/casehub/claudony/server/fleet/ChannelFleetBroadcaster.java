package io.casehub.claudony.server.fleet;

import io.casehub.claudony.server.CaseChannelCreatedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Propagates new Qhorus channels to fleet peers on channel creation.
 *
 * <p>Observes {@link CaseChannelCreatedEvent} synchronously (so it fires for both sync and async
 * event sources) but offloads the HTTP fan-out to a virtual thread per peer to avoid blocking the
 * calling thread during channel creation.
 */
@ApplicationScoped
class ChannelFleetBroadcaster {

    private static final Logger LOG = Logger.getLogger(ChannelFleetBroadcaster.class);

    @Inject PeerRegistry peerRegistry;

    void onCaseChannelCreated(@Observes CaseChannelCreatedEvent event) {
        List<PeerRecord> peers = peerRegistry.getHealthyPeers();
        if (peers.isEmpty()) return;

        var request = new ChannelSyncRequest(event.channelId(), event.channelName());
        for (PeerRecord peer : peers) {
            Thread.ofVirtual().name("channel-sync-" + peer.id()).start(() -> syncToPeer(peer, request));
        }
    }

    private void syncToPeer(PeerRecord peer, ChannelSyncRequest request) {
        try {
            PeerClient client = RestClientBuilder.newBuilder()
                    .baseUri(URI.create(peer.url()))
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .register(FleetKeyClientFilter.class)
                    .build(PeerClient.class);
            Response resp = client.syncChannel(request);
            if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
                peerRegistry.recordSuccess(peer.id());
            } else {
                peerRegistry.recordFailure(peer.id());
                LOG.warnf("Channel sync to peer %s returned %d", peer.url(), resp.getStatus());
            }
        } catch (Exception e) {
            peerRegistry.recordFailure(peer.id());
            LOG.warnf("Channel sync to peer %s failed: %s", peer.url(), e.getMessage());
        }
    }
}
