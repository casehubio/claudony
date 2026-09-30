package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.AgentPoolDefinition;
import io.casehub.claudony.casehub.fleet.AgentPoolDefinitionRegistry;
import io.casehub.claudony.casehub.fleet.AgentPoolManagerRegistry;
import io.casehub.claudony.casehub.fleet.ScalingConfig;
import io.casehub.claudony.casehub.fleet.ScalingScheduler;
import io.casehub.claudony.casehub.fleet.ScalingState;
import io.casehub.platform.api.mcp.HandWrittenEndpoint;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@HandWrittenEndpoint("Pool management — infrastructure CRUD, not domain entities")
@Path("/api/pools")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class PoolResource {

    @Inject AgentPoolManagerRegistry mgrRegistry;
    @Inject AgentPoolDefinitionRegistry defRegistry;
    @Inject ScalingScheduler scalingScheduler;
    @Inject MeterRegistry meterRegistry;

    @GET
    public List<PoolSummary> listPools() {
        return mgrRegistry.poolNames().stream().map(name -> {
            var mgr = mgrRegistry.get(name).orElseThrow();
            var scalingType = defRegistry.get(name)
                .map(d -> d.pool().scaling().type())
                .orElse("none");
            return new PoolSummary(name, mgr.status(), scalingType);
        }).toList();
    }

    @GET @Path("/{name}")
    public Response getPool(@PathParam("name") String name) {
        var mgr = mgrRegistry.get(name).orElse(null);
        if (mgr == null) return Response.status(404).build();

        var def = defRegistry.get(name).orElse(null);
        var scalingState = scalingScheduler.scalingState(name).orElse(null);

        var definitionView = def != null
            ? new PoolDetail.DefinitionView(def.agent(),
                new PoolDetail.PoolConfigView(def.pool().minActive(), def.pool().maxActive(),
                    def.pool().eviction().name()))
            : null;

        var scalingView = buildScalingView(scalingState, def);
        var demandView = buildDemandView(name);

        return Response.ok(new PoolDetail(name, mgr.status(), definitionView, scalingView, demandView)).build();
    }

    @GET @Path("/{name}/sessions")
    public Response listSessions(@PathParam("name") String name) {
        var mgr = mgrRegistry.get(name).orElse(null);
        if (mgr == null) return Response.status(404).build();

        var now = Instant.now();
        var sessions = mgr.sessions().stream().map(s -> new ManagedSessionInfo(
            s.instanceId(), s.identity(), s.workingDir(),
            s.conversationId(), s.state().name(),
            s.lastInteraction(), s.lastMemoryBytes(),
            Duration.between(s.lastInteraction(), now).toSeconds()
        )).toList();

        return Response.ok(sessions).build();
    }

    @jakarta.ws.rs.POST
    @Path("/{name}/sessions/{id}/suspend")
    @jakarta.annotation.security.RolesAllowed("admin")
    public Response suspendSession(@PathParam("name") String name, @PathParam("id") String id) {
        var mgr = mgrRegistry.get(name).orElse(null);
        if (mgr == null) {return Response.status(404).build();}
        try {
            mgr.suspendSession(id);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(404).build();
        }
    }

    @jakarta.ws.rs.POST
    @Path("/{name}/sessions/{id}/resume")
    @jakarta.annotation.security.RolesAllowed("admin")
    public Response resumeSession(@PathParam("name") String name, @PathParam("id") String id) {
        var mgr = mgrRegistry.get(name).orElse(null);
        if (mgr == null) {return Response.status(404).build();}
        try {
            mgr.resumeSession(id);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(404).build();
        }
    }

    @jakarta.ws.rs.DELETE
    @Path("/{name}/sessions/{id}")
    @jakarta.annotation.security.RolesAllowed("admin")
    public Response destroySession(@PathParam("name") String name, @PathParam("id") String id) {
        var mgr = mgrRegistry.get(name).orElse(null);
        if (mgr == null) {return Response.status(404).build();}
        try {
            mgr.destroySession(id);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(404).build();
        }
    }

    @jakarta.ws.rs.PATCH
    @Path("/{name}/capacity")
    @jakarta.annotation.security.RolesAllowed("admin")
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    public Response updateCapacity(@PathParam("name") String name, CapacityUpdate update) {
        var mgr = mgrRegistry.get(name).orElse(null);
        if (mgr == null) {return Response.status(404).build();}
        if (update.maxActive() != null) {
            mgr.adjustMaxActive(update.maxActive());
        }
        if (update.minActive() != null) {
            defRegistry.updateCapacity(name,
                                       update.minActive(),
                                       update.maxActive() != null ? update.maxActive() : mgr.status().max());
        }
        return Response.ok(update).build();
    }

    @jakarta.ws.rs.PATCH
    @Path("/{name}/scaling")
    @jakarta.annotation.security.RolesAllowed("admin")
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    public Response updateScaling(@PathParam("name") String name, ScalingConfigUpdate update) {
        var mgr = mgrRegistry.get(name).orElse(null);
        if (mgr == null) {return Response.status(404).build();}
        var newConfig = parseScalingConfig(update);
        try {
            defRegistry.updateScaling(name, newConfig);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
        scalingScheduler.invalidatePolicy(name);
        return Response.ok(update).build();
    }

    private ScalingConfig parseScalingConfig(ScalingConfigUpdate u) {
        var cooldown        = u.cooldown() != null ? parseDuration(u.cooldown()) : null;
        var scaleInCooldown = u.scaleInCooldown() != null ? parseDuration(u.scaleInCooldown()) : null;
        return switch (u.type()) {
            case "target-tracking" -> new ScalingConfig.TargetTrackingConfig(
                    u.targetFillRatio() != null ? u.targetFillRatio() : 0.7, cooldown, scaleInCooldown);
            case "none" -> ScalingConfig.NoScalingConfig.INSTANCE;
            default -> throw new jakarta.ws.rs.BadRequestException("Unknown scaling type: " + u.type());
        };
    }

    private Duration parseDuration(String s) {
        if (s.endsWith("s")) {return Duration.ofSeconds(Long.parseLong(s.replace("s", "")));}
        if (s.endsWith("m")) {return Duration.ofMinutes(Long.parseLong(s.replace("m", "")));}
        return Duration.ofSeconds(Long.parseLong(s));
    }


    private PoolDetail.ScalingView buildScalingView(ScalingState state, AgentPoolDefinition def) {
        if (state == null && def == null) return null;
        var config = state != null ? state.config() : (def != null ? def.pool().scaling() : null);
        var type = config != null ? config.type() : "none";
        var lastDecision = state != null && state.lastDecision() != null
            ? new PoolDetail.DecisionView(
                state.lastDecision().direction().name(),
                state.lastDecision().count(),
                state.lastDecision().reason(),
                state.lastDecisionTime() != null ? state.lastDecisionTime().toString() : null)
            : null;
        var cooldown = state != null ? formatDuration(state.cooldownRemaining(Instant.now())) : "0s";
        return new PoolDetail.ScalingView(type, config, lastDecision, cooldown);
    }

    private PoolDetail.DemandView buildDemandView(String name) {
        double acquires = counterValue("claudony.pool.acquires.total", name);
        double evictions = counterValue("claudony.pool.evictions.total", name);
        double exhaustions = counterValue("claudony.pool.exhaustions.total", name);
        return new PoolDetail.DemandView(acquires, evictions, exhaustions);
    }

    private double counterValue(String meterName, String poolName) {
        var counter = meterRegistry.find(meterName).tag("pool", poolName).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private String formatDuration(Duration d) {
        return d.isZero() ? "0s" : d.toSeconds() + "s";
    }
}
