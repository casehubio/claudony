package dev.claudony.server.fleet;

import dev.claudony.server.model.SessionResponse;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * Typed REST client for calling another Claudony instance's session API.
 * The fleet key is injected via FleetKeyClientFilter.
 *
 * Note: the base URL is set dynamically per-peer using RestClientBuilder.newBuilder().
 * The quarkus.rest-client.peer-client.url property is a placeholder required by Quarkus
 * for compile-time validation — it is not used at runtime for peer calls.
 */
@RegisterRestClient(configKey = "peer-client")
@RegisterProvider(FleetKeyClientFilter.class)
@Path("/api")
public interface PeerClient {

    /**
     * Fetches sessions from a peer.
     *
     * @param localOnly when true, the peer returns only its own local sessions —
     *                  prevents recursive federation (peer A calls peer B which calls peer A)
     */
    @GET
    @Path("/sessions")
    List<SessionResponse> getSessions(@QueryParam("local") boolean localOnly);

    /**
     * Proxies a terminal resize command to the peer's session.
     * Returns the peer's response status (204 on success, 404 if session not found).
     */
    @POST
    @Path("/sessions/{sessionId}/resize")
    @Consumes(MediaType.WILDCARD)
    Response resize(@PathParam("sessionId") String sessionId,
                    @QueryParam("cols") int cols,
                    @QueryParam("rows") int rows);
}
