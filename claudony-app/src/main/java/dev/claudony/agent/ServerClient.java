package dev.claudony.agent;

import dev.claudony.server.model.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.util.List;

@RegisterRestClient(configKey = "claudony-server")
@RegisterProvider(ApiKeyClientFilter.class)
@Path("/api")
public interface ServerClient {

    @GET
    @Path("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    List<SessionResponse> listSessions();

    @POST
    @Path("/sessions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    SessionResponse createSession(CreateSessionRequest request);

    @DELETE
    @Path("/sessions/{id}")
    void deleteSession(@PathParam("id") String id);

    @PATCH
    @Path("/sessions/{id}/rename")
    @Produces(MediaType.APPLICATION_JSON)
    SessionResponse renameSession(@PathParam("id") String id, @QueryParam("name") String name);

    @POST
    @Path("/sessions/{id}/input")
    @Consumes(MediaType.APPLICATION_JSON)
    void sendInput(@PathParam("id") String id, SendInputRequest request);

    @GET
    @Path("/sessions/{id}/output")
    @Produces(MediaType.TEXT_PLAIN)
    String getOutput(@PathParam("id") String id, @QueryParam("lines") int lines);
}
