package io.casehub.claudony.server.api;

import io.casehub.claudony.server.auth.FleetKeyService;
import io.casehub.claudony.server.fleet.AddPeerRequest;
import io.casehub.claudony.server.fleet.DiscoverySource;
import io.casehub.claudony.server.fleet.FleetKeyClientFilter;
import io.casehub.claudony.server.fleet.ManualRegistrationDiscovery;
import io.casehub.claudony.server.fleet.PeerClient;
import io.casehub.claudony.server.fleet.PeerRecord;
import io.casehub.claudony.server.fleet.PeerRegistry;
import io.casehub.claudony.server.fleet.UpdatePeerRequest;
import io.casehub.claudony.server.model.SessionResponse;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

@McpDomain(value = "claudony/peers", app = "claudony", basePath = "/api/claudony/peers")
@ApplicationScoped
public class ClaudonyPeerApi {

    private static final Logger LOG = Logger.getLogger(ClaudonyPeerApi.class);

    @Inject PeerRegistry registry;
    @Inject ManualRegistrationDiscovery manual;
    @Inject FleetKeyService fleetKeyService;

    @PlatformQuery("List peers")
    @RestPath("/")
    public List<PeerRecord> listPeers() {
        return registry.getAllPeers();
    }

    @PlatformMutation("Add a peer")
    @RestPath("/add")
    public PeerRecord addPeer(AddPeerRequest request) {
        if (request.url() == null || request.url().isBlank()) {
            throw new BadRequestException("url is required");
        }
        return manual.addPeer(request.url(), request.name(), request.terminalMode());
    }

    @PlatformMutation("Delete a peer")
    @RestPath("/{id}/delete")
    public void deletePeer(@PathParam String id) {
        var peer = registry.findById(id)
                .orElseThrow(() -> new NotFoundException("Peer not found: " + id));
        if (peer.source() == DiscoverySource.CONFIG) {
            throw new ForbiddenException("Cannot remove a peer registered via static config");
        }
        registry.removePeer(id);
    }

    @PlatformMutation("Update a peer")
    @RestPath("/{id}/update")
    public PeerRecord updatePeer(@PathParam String id, UpdatePeerRequest request) {
        if (registry.findById(id).isEmpty()) {
            throw new NotFoundException("Peer not found: " + id);
        }
        registry.updatePeer(id, request.name(), request.terminalMode());
        return registry.findById(id)
                .orElseThrow(() -> new NotFoundException("Peer not found after update: " + id));
    }

    @PlatformQuery("Get sessions from a peer")
    @RestPath("/{id}/sessions")
    public List<SessionResponse> peerSessions(@PathParam String id) {
        registry.findById(id)
                .orElseThrow(() -> new NotFoundException("Peer not found: " + id));
        return registry.getCachedSessions(id);
    }

    @PlatformMutation("Ping a peer")
    @RestPath("/{id}/ping")
    public PingResult pingPeer(@PathParam String id) {
        if (registry.findById(id).isEmpty()) {
            throw new NotFoundException("Peer not found: " + id);
        }
        Thread.ofVirtual().start(() -> {
            registry.getAllEntries().stream()
                    .filter(e -> e.id.equals(id))
                    .findFirst()
                    .ifPresent(entry -> {
                        try {
                            var client = RestClientBuilder.newBuilder()
                                    .baseUri(URI.create(entry.url))
                                    .connectTimeout(5, TimeUnit.SECONDS)
                                    .readTimeout(5, TimeUnit.SECONDS)
                                    .register(FleetKeyClientFilter.class)
                                    .build(PeerClient.class);
                            var sessions = client.getSessions(true);
                            registry.recordSuccess(id);
                            registry.updateCachedSessions(id, sessions);
                        } catch (Exception e) {
                            registry.recordFailure(id);
                            LOG.debugf("Fleet ping failed for %s: %s", entry.url, e.getMessage());
                        }
                    });
        });
        return new PingResult("ping dispatched");
    }

    @PlatformMutation("Resize a remote session")
    @RestPath("/{peerId}/sessions/{sessionId}/resize")
    public void resizeRemoteSession(@PathParam String peerId, @PathParam String sessionId,
                                     @QueryParam("cols") Integer cols,
                                     @QueryParam("rows") Integer rows) {
        var peer = registry.findById(peerId)
                .orElseThrow(() -> new NotFoundException("Peer not found: " + peerId));
        var client = RestClientBuilder.newBuilder()
                .baseUri(URI.create(peer.url()))
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .register(FleetKeyClientFilter.class)
                .build(PeerClient.class);
        int c = cols != null ? cols : 80;
        int r = rows != null ? rows : 24;
        client.resize(sessionId, c, r);
    }

    @PlatformMutation("Generate a fleet key")
    @RestPath("/generate-fleet-key")
    public String generateFleetKey() {
        try {
            var key = fleetKeyService.generateAndSave();
            LOG.info("Fleet key generated via API — distribute to all fleet members via CLAUDONY_FLEET_KEY.");
            return key;
        } catch (IOException e) {
            throw new RuntimeException("Could not write fleet key: " + e.getMessage(), e);
        }
    }

    public record PingResult(String status) {}
}
