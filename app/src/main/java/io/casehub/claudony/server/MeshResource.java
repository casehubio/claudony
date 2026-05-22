package io.casehub.claudony.server;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.claudony.config.ClaudonyConfig;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

@Path("/api/mesh")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class MeshResource {

    private static final Logger LOG = Logger.getLogger(MeshResource.class);

    // FAILURE intentionally excluded: human operators signal decisions, not automated failure states.
    private static final Set<MessageType> VALID_HUMAN_TYPES = Set.of(
            MessageType.QUERY, MessageType.COMMAND, MessageType.RESPONSE,
            MessageType.STATUS, MessageType.DECLINE, MessageType.HANDOFF,
            MessageType.DONE, MessageType.EVENT);

    record MeshConfig(String strategy, int interval, int cursorStalenessMinutes) {}
    record PostMessageRequest(String content, String type) {}

    @Inject ClaudonyConfig         config;
    @Inject QhorusDashboardService dashboard;
    @Inject ObjectMapper           mapper;
    @Inject SecurityIdentity       securityIdentity;
    @Inject PreferenceProvider     preferenceProvider;
    @Inject ClaudonyChannelBackend channelBackend;
    @Inject ChannelGateway         gateway;
    @Inject ReactiveChannelService channelService;

    private final ConcurrentHashMap<UUID, Object> channelRegistrationLocks = new ConcurrentHashMap<>();

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
                                    return mapper.writeValueAsString(Map.of(
                                            "channels", ch,
                                            "instances", inst,
                                            "feed", f));
                                } catch (Exception e) {
                                    return "{}";
                                }
                            })
                            .onFailure().recoverWithItem("{}");
                });
    }

    @GET
    @Path("/channels/{name}/events")
    @Produces("text/event-stream")
    @io.smallrye.common.annotation.Blocking
    public Multi<String> channelEvents(
            @PathParam("name") String channelName,
            @QueryParam("after") @DefaultValue("0") long after) {
        // Resolve channel synchronously before returning the Multi — RESTEasy Reactive
        // sends 200 + text/event-stream headers before the first emission, so any
        // NotFoundException thrown inside transformToMulti arrives too late to affect
        // the status code. @Blocking + await() here gives us a real 404.
        var opt = channelService.findByName(channelName).await().indefinitely();
        if (opt.isEmpty()) {
            throw new NotFoundException("Channel not found: " + channelName);
        }
        var channel = opt.get();
        var channelId = channel.id;

        // Per-channel lock prevents concurrent SSE opens from duplicating human_observer
        // registration. Remove when ChannelGateway guards human_observer duplicates (#131).
        ChannelRef ref = new ChannelRef(channelId, channelName);
        synchronized (channelRegistrationLocks.computeIfAbsent(channelId, k -> new Object())) {
            gateway.deregisterBackend(channelId, ClaudonyChannelBackend.BACKEND_ID);
            channelBackend.open(ref, Map.of());
            gateway.registerBackend(channelId, channelBackend, "human_observer");
        }

        AtomicLong lastSentId = new AtomicLong(after);

        // Initial catch-up: fetch messages since cursor
        Multi<String> catchUp = Multi.createFrom().uni(
                dashboard.getTimeline(channelName, lastSentId.get(), 50)
                        .invoke(entries -> updateLastSentId(lastSentId, entries))
                        .map(entries -> entries.isEmpty() ? null : serializeEntries(entries))
        ).filter(Objects::nonNull);

        // Live: poll every 500ms for new messages since lastSentId.
        // The SSE-via-ticks approach is used here because the ChannelEventBus emitter
        // cross-thread emit (vert.x-eventloop-thread-X → response owned by thread-Y)
        // caused the emitted SSE frame to not be flushed to the browser reliably.
        Multi<String> live = Multi.createFrom().ticks().every(Duration.ofMillis(500))
                .onItem().transformToUniAndConcatenate(tick ->
                        dashboard.getTimeline(channelName, lastSentId.get(), 50)
                                .invoke(entries -> updateLastSentId(lastSentId, entries))
                                .map(entries -> entries.isEmpty() ? null : serializeEntries(entries))
                                .onFailure().invoke(e -> LOG.warnf(
                                        "channelEvents tick failed for '%s': %s",
                                        channelName, e.getMessage()))
                                .onFailure().recoverWithItem((String) null)
                )
                .filter(Objects::nonNull);

        // onTermination on the concatenated Multi covers both early disconnect during
        // catchUp (where live was never subscribed) and normal SSE stream termination.
        return Multi.createBy().concatenating().streams(catchUp, live)
                .onTermination().invoke(() ->
                        gateway.deregisterBackend(channelId, ClaudonyChannelBackend.BACKEND_ID));
    }

    private static void updateLastSentId(AtomicLong lastSentId,
                                         List<Map<String, Object>> entries) {
        entries.stream()
                .map(e -> e.get("id"))
                .filter(id -> id instanceof Number)
                .mapToLong(id -> ((Number) id).longValue())
                .max()
                .ifPresent(lastSentId::set);
    }

    private String serializeEntries(List<Map<String, Object>> entries) {
        try {
            return mapper.writeValueAsString(entries);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.errorf("Failed to serialize channel timeline entries: %s", e.getMessage());
            return null;
        }
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
