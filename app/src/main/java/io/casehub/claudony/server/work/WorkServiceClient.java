package io.casehub.claudony.server.work;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@RegisterRestClient(configKey = "work-service")
@Path("/workitems")
public interface WorkServiceClient {

    @GET
    @Path("/inbox")
    List<WorkServiceResponse> inbox(
            @QueryParam("assignee") String assignee,
            @QueryParam("candidateUser") String candidateUser,
            @QueryParam("candidateGroup") List<String> candidateGroups);
}
