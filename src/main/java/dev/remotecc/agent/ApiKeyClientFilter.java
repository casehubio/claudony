package dev.remotecc.agent;

import dev.remotecc.server.auth.ApiKeyService;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class ApiKeyClientFilter implements ClientRequestFilter {

    @Inject
    ApiKeyService apiKeyService;

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        apiKeyService.getKey().ifPresent(key ->
            requestContext.getHeaders().add("X-Api-Key", key));
    }
}
