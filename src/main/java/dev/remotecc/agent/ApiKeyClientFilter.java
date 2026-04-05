package dev.remotecc.agent;

import dev.remotecc.config.RemoteCCConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class ApiKeyClientFilter implements ClientRequestFilter {

    @Inject
    RemoteCCConfig config;

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        config.agentApiKey().ifPresent(key ->
            requestContext.getHeaders().add("X-Api-Key", key));
    }
}
