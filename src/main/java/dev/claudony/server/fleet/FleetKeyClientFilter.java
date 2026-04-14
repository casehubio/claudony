package dev.claudony.server.fleet;

import dev.claudony.server.auth.FleetKeyService;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;

import java.io.IOException;

/**
 * Injects the fleet key as X-Api-Key header on outbound peer REST client calls.
 * Registered specifically on PeerClient via @RegisterProvider — does NOT apply globally.
 */
public class FleetKeyClientFilter implements ClientRequestFilter {

    @Inject
    FleetKeyService fleetKeyService;

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        fleetKeyService.getKey().ifPresent(key ->
                requestContext.getHeaders().putSingle("X-Api-Key", key));
    }
}
