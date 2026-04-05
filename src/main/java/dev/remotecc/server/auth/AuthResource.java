package dev.remotecc.server.auth;

import dev.remotecc.config.RemoteCCConfig;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.logging.Logger;
import java.util.Map;

@Path("/auth")
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class);

    @Inject InviteService inviteService;
    @Inject CredentialStore credentialStore;
    @Inject RemoteCCConfig config;

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
        return Response.ok(Map.of("url", url)).build();
    }

    /** Dev-mode only: sets a cookie so the browser authenticates without WebAuthn. */
    @POST
    @Path("/dev-login")
    @Produces(MediaType.APPLICATION_JSON)
    public Response devLogin() {
        if (LaunchMode.current() != LaunchMode.DEVELOPMENT) {
            return Response.status(404).build();
        }
        var key = config.agentApiKey();
        if (key.isEmpty()) {
            return Response.status(503)
                .entity(Map.of("error", "No dev API key configured — add %dev.remotecc.agent.api-key to application.properties"))
                .build();
        }
        var cookie = new NewCookie.Builder("remotecc-dev-key")
            .value(key.get())
            .path("/")
            .httpOnly(true)
            .build();
        return Response.ok(Map.of("ok", true)).cookie(cookie).build();
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
        inviteService.consume(token);   // consume after validation, before serving page
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
