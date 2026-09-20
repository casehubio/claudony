package io.casehub.claudony.server.api;

import io.casehub.claudony.server.fleet.AddPeerRequest;
import io.casehub.claudony.server.fleet.PeerResource;
import io.casehub.claudony.server.fleet.UpdatePeerRequest;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.QueryParam;

@McpDomain(value = "claudony/peers", basePath = "/api/claudony/peers")
@ApplicationScoped
public class ClaudonyPeerApi {

    @Inject PeerResource resource;

    @PlatformQuery("List peers")
    @RestPath("/")
    public Object listPeers() {
        return resource.list();
    }

    @PlatformMutation("Add a peer")
    @RestPath("/add")
    public Object addPeer(AddPeerRequest request) {
        return resource.add(request).getEntity();
    }

    @PlatformMutation("Delete a peer")
    @RestPath("/{id}/delete")
    public Object deletePeer(@PathParam String id) {
        return resource.delete(id).getEntity();
    }

    @PlatformMutation("Update a peer")
    @RestPath("/{id}/update")
    public Object updatePeer(@PathParam String id, UpdatePeerRequest request) {
        return resource.update(id, request).getEntity();
    }

    @PlatformQuery("Get sessions from a peer")
    @RestPath("/{id}/sessions")
    public Object peerSessions(@PathParam String id) {
        return resource.peerSessions(id).getEntity();
    }

    @PlatformMutation("Ping a peer")
    @RestPath("/{id}/ping")
    public Object pingPeer(@PathParam String id) {
        return resource.ping(id).getEntity();
    }

    @PlatformMutation("Resize a remote session")
    @RestPath("/{peerId}/sessions/{sessionId}/resize")
    public Object resizeRemoteSession(@PathParam String peerId, @PathParam String sessionId,
                                       @QueryParam("cols") Integer cols,
                                       @QueryParam("rows") Integer rows) {
        return resource.proxyResize(peerId, sessionId, cols, rows).getEntity();
    }

    @PlatformMutation("Generate a fleet key")
    @RestPath("/generate-fleet-key")
    public Object generateFleetKey() {
        return resource.generateFleetKey().getEntity();
    }
}
