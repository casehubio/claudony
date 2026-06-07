package io.casehub.claudony.server;

import io.casehub.claudony.casehub.ResearcherCase;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Path("/api/casehub")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class CasehubResource {

    @Inject
    Instance<ResearcherCase> researcherCase;

    @POST
    @Path("/cases/researcher")
    public CompletionStage<Response> startResearcher() {
        if (researcherCase.isUnsatisfied()) {
            return CompletableFuture.completedFuture(
                Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "CaseHub engine not available"))
                    .build());
        }
        return researcherCase.get()
            .startCase()
            .thenApply(caseId -> Response.accepted(new CaseStartedResponse(caseId)).build());
    }

    record CaseStartedResponse(UUID caseId) {}
}
