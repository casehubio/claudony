package dev.claudony.server.fleet;

import dev.claudony.server.model.SessionResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
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
}
