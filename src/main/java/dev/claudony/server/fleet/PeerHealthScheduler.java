package dev.claudony.server.fleet;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class PeerHealthScheduler {

    private static final Logger LOG = Logger.getLogger(PeerHealthScheduler.class);

    @Inject PeerRegistry registry;

    @Scheduled(every = "30s", delayed = "10s")
    void healthCheckLoop() {
        for (var entry : registry.getAllEntries()) {
            if (!entry.shouldAttemptHealthCheck()) continue;
            Thread.ofVirtual().start(() -> checkPeer(entry));
        }
    }

    private void checkPeer(PeerEntry entry) {
        try {
            var client = RestClientBuilder.newBuilder()
                    .baseUri(URI.create(entry.url))
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .register(FleetKeyClientFilter.class)
                    .build(PeerClient.class);
            var sessions = client.getSessions(true);
            registry.recordSuccess(entry.id);
            registry.updateCachedSessions(entry.id, sessions);
            LOG.debugf("Fleet health OK: %s (%d sessions)", entry.url, sessions.size());
        } catch (Exception e) {
            registry.recordFailure(entry.id);
            LOG.debugf("Fleet health FAIL: %s — %s", entry.url, e.getMessage());
        }
    }
}
