package dev.remotecc.server.auth;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.logging.Logger;

@Path("/auth")
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class);

    @Inject InviteService inviteService;
    @Inject CredentialStore credentialStore;

    @POST
    @Path("/invite")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response generateInvite(@Context UriInfo uriInfo) {
        var token = inviteService.generate();
        var url = uriInfo.getBaseUriBuilder()
            .path("auth/register")
            .queryParam("token", token)
            .build()
            .toString();
        LOG.infof("Generated invite link (token=%s...)", token.substring(0, 8));
        return Response.ok("{\"url\":\"" + url + "\"}").build();
    }

    @GET
    @Path("/register")
    @Produces(MediaType.TEXT_HTML)
    public Response registerPage(@QueryParam("token") String token) {
        if (credentialStore.isEmpty()) {
            return serveRegisterPage();
        }
        if (token == null || !inviteService.isValid(token)) {
            LOG.warnf("Invalid or missing invite token: %s", token);
            return Response.status(403)
                .entity("<html><body><h2>This invite has expired — ask for a new one.</h2></body></html>")
                .type(MediaType.TEXT_HTML)
                .build();
        }
        return serveRegisterPage();
    }

    @GET
    @Path("/login")
    @Produces(MediaType.TEXT_HTML)
    public Response loginPage() {
        try {
            var stream = getClass().getResourceAsStream("/META-INF/resources/auth/login.html");
            if (stream == null) return Response.serverError().entity("login.html not found").build();
            return Response.ok(new String(stream.readAllBytes())).type(MediaType.TEXT_HTML).build();
        } catch (Exception e) {
            return Response.serverError().build();
        }
    }

    private Response serveRegisterPage() {
        try {
            var stream = getClass().getResourceAsStream("/META-INF/resources/auth/register.html");
            if (stream == null) return Response.serverError().entity("register.html not found").build();
            return Response.ok(new String(stream.readAllBytes())).type(MediaType.TEXT_HTML).build();
        } catch (Exception e) {
            return Response.serverError().build();
        }
    }
}
