package io.casehub.claudony.server;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.claudony.config.ClaudonyConfig;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

@Path("/api/mesh")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class MeshResource {

    // FAILURE intentionally excluded: human operators signal decisions, not automated failure states.
    private static final Set<MessageType> VALID_HUMAN_TYPES = Set.of(
            MessageType.QUERY, MessageType.COMMAND, MessageType.RESPONSE,
            MessageType.STATUS, MessageType.DECLINE, MessageType.HANDOFF,
            MessageType.DONE, MessageType.EVENT);

    record MeshConfig(String strategy, int interval, int cursorStalenessMinutes) {}
    record PostMessageRequest(String content, String type) {}

    @Inject ClaudonyConfig config;
    @Inject QhorusDashboardService dashboard;
    @Inject ObjectMapper mapper;
    @Inject SecurityIdentity securityIdentity;
    @Inject PreferenceProvider preferenceProvider;

    @GET
    @Path("/config")
    public MeshConfig config() {
        int staleness = preferenceProvider
                .resolve(SettingsScope.of("casehubio", "claudony"))
                .getOrDefault(ChannelCursorStaleness.KEY)
                .minutes();
        return new MeshConfig(config.meshRefreshStrategy(), config.meshRefreshInterval(), staleness);
    }

    @GET
    @Path("/channels")
    public Uni<List<QhorusDashboardService.ChannelView>> channels() {
        return dashboard.listChannels();
    }

    @GET
    @Path("/instances")
    public Uni<List<QhorusDashboardService.InstanceView>> instances() {
        return dashboard.listInstances();
    }

    @GET
    @Path("/channels/{name}/timeline")
    public Uni<List<Map<String, Object>>> timeline(
            @PathParam("name") String name,
            @QueryParam("after") Long after,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return dashboard.getTimeline(name, after, limit);
    }

    @GET
    @Path("/feed")
    public Uni<List<Map<String, Object>>> feed(
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return dashboard.getFeed(limit);
    }

    @GET
    @Path("/events")
    @Produces("text/event-stream")
    public Multi<String> events() {
        long intervalMs = config.meshRefreshInterval();
        return Multi.createFrom().ticks().every(Duration.ofMillis(intervalMs))
                .onItem().transformToUniAndConcatenate(tick -> {
                    Uni<List<QhorusDashboardService.ChannelView>> channels =
                            dashboard.listChannels().onFailure().recoverWithItem(List.of());
                    Uni<List<QhorusDashboardService.InstanceView>> instances =
                            dashboard.listInstances().onFailure().recoverWithItem(List.of());
                    Uni<List<Map<String, Object>>> feed =
                            dashboard.getFeed(100).onFailure().recoverWithItem(List.of());
                    return Uni.combine().all().unis(channels, instances, feed)
                            .combinedWith((ch, inst, f) -> {
                                try {
                                    return "data: " + mapper.writeValueAsString(Map.of(
                                            "channels", ch,
                                            "instances", inst,
                                            "feed", f)) + "\n\n";
                                } catch (Exception e) {
                                    return "data: {}\n\n";
                                }
                            })
                            .onFailure().recoverWithItem("data: {}\n\n");
                });
    }

    @POST
    @Path("/channels/{name}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> postMessage(
            @PathParam("name") String name,
            PostMessageRequest req) {
        if (req == null || req.content() == null || req.content().isBlank()) {
            return Uni.createFrom().item(Response.status(400).entity("content must not be blank").build());
        }
        MessageType type;
        try {
            type = MessageType.valueOf((req.type() == null ? "status" : req.type()).toUpperCase());
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(
                    Response.status(400).entity("invalid type: " + req.type()).build());
        }
        if (!VALID_HUMAN_TYPES.contains(type)) {
            return Uni.createFrom().item(
                    Response.status(400).entity("invalid type: " + req.type()).build());
        }
        String sender = "human:" + securityIdentity.getPrincipal().getName();
        return dashboard.sendHumanMessage(name, sender, type, req.content())
                .map(result -> Response.ok(result).build())
                .onFailure(IllegalArgumentException.class)
                    .recoverWithItem(e -> Response.status(404).entity(e.getMessage()).build())
                .onFailure(IllegalStateException.class)
                    .recoverWithItem(e -> Response.status(409).entity(e.getMessage()).build());
    }
}
