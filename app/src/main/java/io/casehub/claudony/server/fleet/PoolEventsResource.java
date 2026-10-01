package io.casehub.claudony.server.fleet;

import io.casehub.platform.api.mcp.HandWrittenEndpoint;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;

@HandWrittenEndpoint("SSE streaming — cannot be expressed as mcpDomain")
@Path("/api/pool-events")
@Authenticated
public class PoolEventsResource {

    @Inject PoolEventBus poolEventBus;
    @Inject PoolService poolService;

    @GET
    @Path("/{name}")
    @Produces("text/event-stream")
    public Multi<String> poolEvents(@PathParam("name") String name) {
        poolService.getPool(name);
        return poolEventBus.subscribe(name, () -> poolService.buildPoolSnapshot(name));
    }
}
