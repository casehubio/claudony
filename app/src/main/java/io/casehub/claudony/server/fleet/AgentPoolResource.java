package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.AgentPoolStatus;
import io.casehub.claudony.casehub.fleet.ClaudonyAgentBackend;
import io.casehub.platform.api.mcp.HandWrittenEndpoint;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@HandWrittenEndpoint("Agent pool observability — internal infrastructure, not domain CRUD")
@Path("/api/agent-pools")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class AgentPoolResource {

    @Inject
    ClaudonyAgentBackend claudonyBackend;

    @GET
    public AgentPoolStatus poolStatus() {
        return claudonyBackend.poolStatus();
    }
}
