package dev.remotecc.server;

import dev.remotecc.config.RemoteCCConfig;
import dev.remotecc.server.model.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.logging.Logger;
import java.time.Instant;
import java.util.UUID;

@Path("/api/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SessionResource {

    private static final Logger LOG = Logger.getLogger(SessionResource.class);

    @Inject RemoteCCConfig config;
    @Inject SessionRegistry registry;
    @Inject TmuxService tmux;

    @GET
    public java.util.List<SessionResponse> list() {
        return registry.all().stream()
                .map(s -> SessionResponse.from(s, config.port()))
                .toList();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        return registry.find(id)
                .map(s -> Response.ok(SessionResponse.from(s, config.port())).build())
                .orElse(Response.status(404).build());
    }

    @POST
    public Response create(CreateSessionRequest req) {
        var id = UUID.randomUUID().toString();
        var name = config.tmuxPrefix() + req.name();
        var command = req.effectiveCommand(config.claudeCommand());
        var now = Instant.now();
        var session = new Session(id, name, req.workingDir(), command, SessionStatus.IDLE, now, now);
        try {
            tmux.createSession(name, req.workingDir(), command);
            registry.register(session);
            LOG.infof("Created session '%s' (id=%s)", name, id);
            return Response.status(201)
                    .entity(SessionResponse.from(session, config.port()))
                    .build();
        } catch (Exception e) {
            LOG.errorf("Failed to create session '%s': %s", name, e.getMessage());
            return Response.serverError()
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        return registry.find(id).map(session -> {
            try {
                tmux.killSession(session.name());
                registry.remove(id);
                LOG.infof("Deleted session '%s' (id=%s)", session.name(), id);
                return Response.noContent().build();
            } catch (Exception e) {
                LOG.errorf("Failed to delete session: %s", e.getMessage());
                return Response.serverError().build();
            }
        }).orElse(Response.status(404).build());
    }

    @PATCH
    @Path("/{id}/rename")
    public Response rename(@PathParam("id") String id, @QueryParam("name") String newName) {
        return registry.find(id).map(session -> {
            try {
                var newTmuxName = config.tmuxPrefix() + newName;
                var p = new ProcessBuilder("tmux", "rename-session", "-t", session.name(), newTmuxName)
                        .redirectErrorStream(true).start();
                p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                p.waitFor();
                var renamed = new Session(id, newTmuxName, session.workingDir(),
                        session.command(), session.status(), session.createdAt(), Instant.now());
                registry.register(renamed);
                return Response.ok(SessionResponse.from(renamed, config.port())).build();
            } catch (Exception e) {
                return Response.serverError().build();
            }
        }).orElse(Response.status(404).build());
    }
}
