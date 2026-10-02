package io.casehub.claudony.server.fleet;

import io.casehub.platform.api.mcp.HandWrittenEndpoint;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Deprecated(since = "0.3", forRemoval = true)
@HandWrittenEndpoint("Pool management — migrated to ClaudonyPoolApi mcpDomain")
@Path("/api/pools")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class PoolResource {

    @Inject PoolService poolService;

    @GET
    public List<PoolSummary> listPools() {
        return poolService.listPools();
    }

    @GET @Path("/{name}")
    public Response getPool(@PathParam("name") String name) {
        try {
            return Response.ok(poolService.getPool(name)).build();
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Response.status(404).build();
        }
    }

    @GET @Path("/{name}/sessions")
    public Response listSessions(@PathParam("name") String name) {
        try {
            return Response.ok(poolService.listSessions(name)).build();
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Response.status(404).build();
        }
    }

    @jakarta.ws.rs.POST
    @Path("/{name}/sessions/{id}/suspend")
    @jakarta.annotation.security.RolesAllowed("admin")
    public Response suspendSession(@PathParam("name") String name, @PathParam("id") String id) {
        try {
            poolService.suspendSession(name, id);
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(404).build();
        }
    }

    @jakarta.ws.rs.POST
    @Path("/{name}/sessions/{id}/resume")
    @jakarta.annotation.security.RolesAllowed("admin")
    public Response resumeSession(@PathParam("name") String name, @PathParam("id") String id) {
        try {
            poolService.resumeSession(name, id);
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(404).build();
        }
    }

    @jakarta.ws.rs.DELETE
    @Path("/{name}/sessions/{id}")
    @jakarta.annotation.security.RolesAllowed("admin")
    public Response destroySession(@PathParam("name") String name, @PathParam("id") String id) {
        try {
            poolService.destroySession(name, id);
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(404).build();
        }
    }

    @jakarta.ws.rs.PATCH
    @Path("/{name}/capacity")
    @jakarta.annotation.security.RolesAllowed("admin")
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    public Response updateCapacity(@PathParam("name") String name, CapacityUpdate update) {
        var req = new PoolUpdateRequest(update.minActive(), update.maxActive(),
                null, null, null, null, null, null, null, null);
        try {
            poolService.updatePool(name, req);
            return Response.ok(update).build();
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Response.status(404).build();
        }
    }

    @jakarta.ws.rs.PATCH
    @Path("/{name}/scaling")
    @jakarta.annotation.security.RolesAllowed("admin")
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    public Response updateScaling(@PathParam("name") String name, ScalingConfigUpdate update) {
        var req = new PoolUpdateRequest(null, null, update.type(), update.targetFillRatio(),
                null, null, null, null, update.cooldown(), update.scaleInCooldown());
        try {
            poolService.updatePool(name, req);
            return Response.ok(update).build();
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (jakarta.ws.rs.BadRequestException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }
}
