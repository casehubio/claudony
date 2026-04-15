package dev.claudony.server.fleet;

import dev.claudony.server.auth.FleetKeyService;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Path("/api/peers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class PeerResource {

    private static final Logger LOG = Logger.getLogger(PeerResource.class);

    @Inject PeerRegistry registry;
    @Inject ManualRegistrationDiscovery manual;
    @Inject FleetKeyService fleetKeyService;

    @GET
    public List<PeerRecord> list() {
        return registry.getAllPeers();
    }

    @POST
    public Response add(AddPeerRequest req) {
        if (req.url() == null || req.url().isBlank()) {
            return Response.status(400).entity("{\"error\":\"url is required\"}").build();
        }
        var created = manual.addPeer(req.url(), req.name(), req.terminalMode());
        return Response.status(201).entity(created).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        return registry.findById(id)
                .map(peer -> {
                    if (peer.source() == DiscoverySource.CONFIG) {
                        return Response.status(405)
                                .entity("{\"error\":\"Cannot remove a peer registered via static config\"}")
                                .build();
                    }
                    registry.removePeer(id);
                    return Response.noContent().build();
                })
                .orElse(Response.status(404).build());
    }

    @PATCH
    @Path("/{id}")
    public Response update(@PathParam("id") String id, UpdatePeerRequest req) {
        if (registry.findById(id).isEmpty()) {
            return Response.status(404).build();
        }
        registry.updatePeer(id, req.name(), req.terminalMode());
        return registry.findById(id)
                .map(updated -> Response.ok(updated).build())
                .orElse(Response.status(404).build());
    }

    @GET
    @Path("/{id}/sessions")
    public Response peerSessions(@PathParam("id") String id) {
        return registry.findById(id)
                .map(peer -> Response.ok(registry.getCachedSessions(id)).build())
                .orElse(Response.status(404).build());
    }

    @POST
    @Path("/{id}/ping")
    public Response ping(@PathParam("id") String id) {
        if (registry.findById(id).isEmpty()) {
            return Response.status(404).build();
        }
        // Dispatches health check asynchronously — returns immediately
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
        return Response.accepted().entity("{\"status\":\"ping dispatched\"}").build();
    }

    @POST
    @Path("/{peerId}/sessions/{sessionId}/resize")
    @Consumes(MediaType.WILDCARD)
    public Response proxyResize(
            @PathParam("peerId") String peerId,
            @PathParam("sessionId") String sessionId,
            @QueryParam("cols") @DefaultValue("80") int cols,
            @QueryParam("rows") @DefaultValue("24") int rows) {

        var peer = registry.findById(peerId);
        if (peer.isEmpty()) {
            return Response.status(404).build();
        }

        var client = RestClientBuilder.newBuilder()
                .baseUri(URI.create(peer.get().url()))
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .register(FleetKeyClientFilter.class)
                .build(PeerClient.class);

        try {
            var peerResponse = client.resize(sessionId, cols, rows);
            return Response.status(peerResponse.getStatus()).build();
        } catch (Exception e) {
            LOG.debugf("Proxy resize failed for peer %s session %s: %s", peerId, sessionId, e.getMessage());
            return Response.status(502).build();
        }
    }

    @POST
    @Path("/generate-fleet-key")
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.WILDCARD)
    public Response generateFleetKey() {
        try {
            var key = fleetKeyService.generateAndSave();
            LOG.info("Fleet key generated via API — distribute to all fleet members via CLAUDONY_FLEET_KEY.");
            return Response.ok(key).build();
        } catch (IOException e) {
            LOG.errorf("Failed to generate fleet key: %s", e.getMessage());
            return Response.serverError()
                    .entity("{\"error\":\"Could not write fleet key: " + e.getMessage() + "\"}")
                    .build();
        }
    }
}
