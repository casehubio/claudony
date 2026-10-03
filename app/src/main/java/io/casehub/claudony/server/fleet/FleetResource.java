package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.script.FleetScriptResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/claudony/fleet")
@ApplicationScoped
public class FleetResource {

    @Inject
    FleetScriptService service;

    @POST
    @Path("/execute")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public FleetScriptResult execute(String yaml) {
        return service.execute(yaml);
    }
}
