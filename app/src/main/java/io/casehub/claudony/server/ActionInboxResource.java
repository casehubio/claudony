package io.casehub.claudony.server;

import io.casehub.claudony.casehub.inbox.ActionAggregationService;
import io.casehub.platform.api.mcp.HandWrittenEndpoint;
import io.casehub.claudony.casehub.inbox.ActionInboxResponse;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@HandWrittenEndpoint("Blocking aggregation — pending @McpDomain migration")
@Path("/api/actions")
@Produces(MediaType.APPLICATION_JSON)
public class ActionInboxResource {

    @Inject
    ActionAggregationService aggregationService;

    @GET
    @Blocking
    public ActionInboxResponse listActions() {
        return aggregationService.listActions();
    }
}
