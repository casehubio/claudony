package io.casehub.claudony.server.ops;

import io.casehub.platform.api.mcp.HandWrittenEndpoint;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

@HandWrittenEndpoint("SSE streaming — cannot be expressed as mcpDomain")
@Path("/api/ops/events")
@Authenticated
public class OpsEventsResource {

    @Inject OpsEventBus opsEventBus;
    @Inject OpsService opsService;

    @GET
    @Produces("text/event-stream")
    public Multi<String> events() {
        return opsEventBus.subscribe(() -> opsService.buildSnapshotJson());
    }
}
