package io.casehub.claudony.server;

import io.casehub.claudony.casehub.AgentCase;
import io.quarkus.security.Authenticated;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.UUID;

@Path("/api/casehub")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class CasehubResource {

    @Inject
    Instance<AgentCase> agentCase;

    @POST
    @Path("/cases/agent")
    public Response startAgent() {
        if (agentCase.isUnsatisfied()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                           .entity(Map.of("error", "CaseHub engine not available"))
                           .build();
        }
        UUID caseId = agentCase.get().startCase();
        return Response.accepted(new CaseStartedResponse(caseId)).build();
    }

    record CaseStartedResponse(UUID caseId) {}
}
