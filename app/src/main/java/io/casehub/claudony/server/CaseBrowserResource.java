package io.casehub.claudony.server;

import io.casehub.claudony.casehub.browser.CaseBrowserService;
import io.casehub.claudony.casehub.browser.CaseSummary;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/cases")
@Produces(MediaType.APPLICATION_JSON)
public class CaseBrowserResource {

    @Inject
    CaseBrowserService caseBrowserService;

    @GET
    @Blocking
    public Map<String, Object> listCases() {
        List<CaseSummary> cases = caseBrowserService.listCases();
        return Map.of("entities", cases, "totalCount", cases.size());
    }

    @GET
    @Path("/{id}")
    @Blocking
    public Response getCaseDetail(@PathParam("id") UUID id) {
        return caseBrowserService.getCaseDetail(id)
                .map(detail -> Response.ok(detail).build())
                .orElse(Response.status(404).build());
    }
}
