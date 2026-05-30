package io.casehub.claudony.server.fleet;

import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CLUSTER-scoped {@link MessageObserver} that relays a channel-name tick to every
 * healthy fleet peer on every Qhorus message dispatch.
 *
 * <p>Tick-only relay: {@link ChannelNotifyRequest} carries only {@code channelName}.
 * Peers retrieve message content from the shared PostgreSQL Qhorus instance.
 *
 * <p>Fires on both blocking {@code MessageService.dispatch()} (pre-commit on that path)
 * and reactive {@code ReactiveMessageService.dispatch()} (post-commit). Pre-commit race
 * on the blocking path is benign — spurious ticks find nothing on peer fetch.
 * Tracked: qhorus#166 (after-commit dispatch for blocking service).
 *
 * <p>Not called for LAST_WRITE overwrites — {@code MessageService} returns before
 * reaching {@code MessageObserverDispatcher} in that case.
 */
@ApplicationScoped
class FleetMessageRelayObserver implements MessageObserver {

    private static final Logger LOG = Logger.getLogger(FleetMessageRelayObserver.class);
    private static final int RELAY_TIMEOUT_S = 5;

    @Inject PeerRegistry peerRegistry;

    @Override
    public Scope scope() {
        return Scope.CLUSTER;
    }

    @Override
    public void onMessage(MessageReceivedEvent event) {
        if (event.channelName() == null) return;
        List<PeerRecord> peers = peerRegistry.getHealthyPeers();
        if (peers.isEmpty()) return;
        var request = new ChannelNotifyRequest(event.channelName());
        for (PeerRecord peer : peers) {
            Thread.ofVirtual().name("channel-notify-" + peer.id())
                    .start(() -> relayToPeer(peer, request));
        }
    }

    private void relayToPeer(PeerRecord peer, ChannelNotifyRequest request) {
        try {
            PeerClient client = RestClientBuilder.newBuilder()
                    .baseUri(URI.create(peer.url()))
                    .connectTimeout(RELAY_TIMEOUT_S, TimeUnit.SECONDS)
                    .readTimeout(RELAY_TIMEOUT_S, TimeUnit.SECONDS)
                    .register(FleetKeyClientFilter.class)
                    .build(PeerClient.class);
            var resp = client.notifyChannel(request);
            if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
                peerRegistry.recordSuccess(peer.id());
            } else {
                peerRegistry.recordFailure(peer.id());
                LOG.warnf("Channel notify to peer %s returned %d", peer.url(), resp.getStatus());
            }
        } catch (Exception e) {
            peerRegistry.recordFailure(peer.id());
            LOG.warnf("Channel notify to peer %s failed: %s", peer.url(), e.getMessage());
        }
    }
}
