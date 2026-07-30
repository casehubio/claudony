package io.casehub.claudony.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.claudony.config.ClaudonyConfig;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.api.instance.InstanceInfo;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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
    @Inject
    ClaudonyConfig         config;
    @Inject
    QhorusDashboardService dashboard;
    @Inject
    ObjectMapper           mapper;
    @Inject
    SecurityIdentity       securityIdentity;
    @Inject
    PreferenceProvider     preferenceProvider;
    @Inject
    ChannelService channelService;
    @Inject
    io.casehub.qhorus.api.store.CommitmentStore commitmentStore;
    @Inject
    io.casehub.qhorus.runtime.message.ReactionService reactionService;
    @Inject
    io.casehub.qhorus.runtime.message.TopicService topicService;
    @Inject
    io.casehub.qhorus.runtime.channel.ChannelMembershipService membershipService;


    private static long maxFeedId(List<Map<String, Object>> feed) {
        return feed.stream()
                   .map(e -> e.get("id"))
                   .filter(id -> id instanceof Number)
                   .mapToLong(id -> ((Number) id).longValue())
                   .max()
                   .orElse(0L);
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

    @GET
    @Path("/config")
    public MeshConfig config() {
        int staleness = preferenceProvider
                                .resolve(SettingsScope.of(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, io.casehub.platform.api.path.Path.of("casehubio", "claudony")))
                                .getOrDefault(ChannelCursorStaleness.KEY)
                                .minutes();
        return new MeshConfig(config.meshRefreshStrategy(), config.meshRefreshInterval(), staleness);
    }

    @GET
    @Path("/channels")
    public List<ChannelDetail> channels() {
        return dashboard.listChannels();
    }

    @GET
    @Path("/instances")
    public List<InstanceInfo> instances() {
        return dashboard.listInstances();
    }

    @GET
    @Path("/channels/{name}/timeline")
    public List<Map<String, Object>> timeline(
            @PathParam("name") String name,
            @QueryParam("after") Long after,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return dashboard.getTimeline(name, after, limit);
    }

    @GET
    @Path("/feed")
    public List<Map<String, Object>> feed(
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return dashboard.getFeed(limit);
    }

    @GET
    @Path("/events")
    @Produces("text/event-stream")
    public Multi<String> events(@QueryParam("after") @DefaultValue("-1") long after) {
        long         intervalMs      = config.meshRefreshInterval();
        final long[] lastDeliveredId = {after};
        return Multi.createFrom().ticks().every(Duration.ofMillis(intervalMs))
                    .onItem().transformToUniAndConcatenate(tick -> {
                    Uni<List<ChannelDetail>> channels =
                            Uni.createFrom().item(() -> {
                                try {
                                    return dashboard.listChannels();
                                } catch (Exception e) {
                                    return List.<ChannelDetail>of();
                                }
                            });
                    Uni<List<InstanceInfo>> instances =
                            Uni.createFrom().item(() -> {
                                try {
                                    return dashboard.listInstances();
                                } catch (Exception e) {
                                    return List.<InstanceInfo>of();
                                }
                            });
                    Uni<List<Map<String, Object>>> feed =
                            Uni.createFrom().item(() -> {
                                try {
                                    return dashboard.getFeed(100);
                                } catch (Exception e) {
                                    return List.<Map<String, Object>>of();
                                }
                            });
                    return Uni.combine().all().unis(channels, instances, feed)
                              .combinedWith((ch, inst, f) -> {
                                  long eventId = maxFeedId(f);
                                  // Skip on first tick only when client already has this state
                                  if (tick == 0L && after >= 0 && eventId <= after) {
                                      return null;
                                  }
                                  lastDeliveredId[0] = eventId;
                                  try {
                                      return mapper.writeValueAsString(Map.of(
                                              "channels", ch,
                                              "instances", inst,
                                              "feed", f,
                                              "_eventId", eventId));
                                  } catch (Exception e) {
                                      return "{}";
                                  }
                              })
                              .onFailure().recoverWithItem("{}");
                })
                    .filter(Objects::nonNull);
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
        // the status code. @Blocking + direct call here gives us a real 404.
        var opt = channelService.findByName(channelName);
        if (opt.isEmpty()) {
            throw new NotFoundException("Channel not found: " + channelName);
        }
        AtomicLong lastSentId = new AtomicLong(after);

        // Initial catch-up: fetch messages since cursor
        Multi<String> catchUp = Multi.createFrom().uni(
                Uni.createFrom().item(() -> dashboard.getTimeline(channelName, lastSentId.get(), 50))
                   .invoke(entries -> updateLastSentId(lastSentId, entries))
                   .map(entries -> entries.isEmpty() ? null : serializeEntries(entries))
                                                      ).filter(Objects::nonNull);

        // Live: poll every 500ms for new messages since lastSentId.
        // The SSE-via-ticks approach is used here because the ChannelEventBus emitter
        // cross-thread emit (vert.x-eventloop-thread-X → response owned by thread-Y)
        // caused the emitted SSE frame to not be flushed to the browser reliably.
        Multi<String> live = Multi.createFrom().ticks().every(Duration.ofMillis(500))
                                  .onItem().transformToUniAndConcatenate(tick ->
                                                                                 Uni.createFrom().item(() -> dashboard.getTimeline(channelName, lastSentId.get(), 50))
                                                                                    .invoke(entries -> updateLastSentId(lastSentId, entries))
                                                                                    .map(entries -> entries.isEmpty() ? null : serializeEntries(entries))
                                                                                    .onFailure().invoke(e -> LOG.warnf(
                                                                                            "channelEvents tick failed for '%s': %s",
                                                                                            channelName, e.getMessage()))
                                                                                    .onFailure().recoverWithItem((String) null)
                                                                        )
                                  .filter(Objects::nonNull);

        return Multi.createBy().concatenating().streams(catchUp, live);
    }

    private String serializeEntries(List<Map<String, Object>> entries) {
        try {
            return mapper.writeValueAsString(entries);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.errorf("Failed to serialize channel timeline entries: %s", e.getMessage());
            return null;
        }
    }

    @GET
    @Path("/channels/{name}/commitments")
    public java.util.List<java.util.Map<String, Object>> commitments(@PathParam("name") String name) {
        var opt = channelService.findByName(name);
        if (opt.isEmpty()) {
            return java.util.List.of();
        }
        var commitments = commitmentStore.findByChannel(opt.get().id());
        return commitments.stream()
                          .map(this::toCommitmentMap)
                          .toList();
    }

    private java.util.Map<String, Object> toCommitmentMap(io.casehub.qhorus.api.message.Commitment c) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", c.id());
        map.put("correlationId", c.correlationId());
        map.put("state", c.state().name());
        map.put("requester", c.requester());
        map.put("obligor", c.obligor());
        map.put("expiresAt", c.expiresAt() != null ? c.expiresAt().toString() : null);
        map.put("acknowledgedAt", c.acknowledgedAt() != null ? c.acknowledgedAt().toString() : null);
        map.put("resolvedAt", c.resolvedAt() != null ? c.resolvedAt().toString() : null);
        map.put("delegatedTo", c.delegatedTo());
        map.put("createdAt", c.createdAt() != null ? c.createdAt().toString() : null);
        return map;
    }

    @POST
    @Path("/channels/{name}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response postMessage(
            @PathParam("name") String name,
            PostMessageRequest req) {
        MessageType type;
        try {
            type = MessageType.valueOf((req == null || req.type() == null ? "status" : req.type()).toUpperCase());
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity("invalid type: " + (req == null ? null : req.type())).build();
        }
        if (!VALID_HUMAN_TYPES.contains(type)) {
            return Response.status(400).entity("invalid type: " + req.type()).build();
        }

        switch (type) {
            case RESPONSE, DONE, DECLINE -> {
                if (req.inReplyTo() == null) {
                    return Response.status(400)
                                   .entity(type.name() + " requires inReplyTo").build();
                }
                if (req.correlationId() == null || req.correlationId().isBlank()) {
                    return Response.status(400)
                                   .entity(type.name() + " requires correlationId").build();
                }
            }
            case HANDOFF -> {
                if (req.inReplyTo() == null) {
                    return Response.status(400)
                                   .entity("HANDOFF requires inReplyTo").build();
                }
                if (req.correlationId() == null || req.correlationId().isBlank()) {
                    return Response.status(400)
                                   .entity("HANDOFF requires correlationId").build();
                }
                if (req.target() == null || req.target().isBlank()) {
                    return Response.status(400)
                                   .entity("HANDOFF requires target").build();
                }
            }
            default -> {}
        }

        boolean contentRequired = type != MessageType.EVENT;
        if (contentRequired && (req == null || req.content() == null || req.content().isBlank())) {
            return Response.status(400).entity("content must not be blank").build();
        }
        String content = (req != null && req.content() != null && !req.content().isBlank()) ? req.content() : null;
        String sender  = "human:" + securityIdentity.getPrincipal().getName();

        java.time.Instant deadline = null;
        if (req != null && req.deadline() != null && !req.deadline().isBlank()) {
            try {
                deadline = java.time.Instant.parse(req.deadline());
            } catch (java.time.format.DateTimeParseException e) {
                return Response.status(400).entity("invalid deadline format").build();
            }
        }

        try {
            var result = dashboard.sendHumanMessage(name, sender, type, content,
                                                    req != null ? req.inReplyTo() : null,
                                                    req != null ? req.correlationId() : null,
                                                    req != null ? req.artefactRefs() : null,
                                                    req != null ? req.target() : null,
                                                    deadline,
                                                    req != null ? req.topic() : null);
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            return Response.status(404).entity(e.getMessage()).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(e.getMessage()).build();
        }
    }

    record ReactionBatchRequest(java.util.List<Long> messageIds) {}

    record AddReactionRequest(String emoji) {}

    @POST
    @Path("/channels/{name}/reactions/batch")
    public Response reactionsBatch(@PathParam("name") String name, ReactionBatchRequest req) {
        var channel = channelService.findByName(name);
        if (channel.isEmpty()) {return Response.status(404).build();}
        if (req == null || req.messageIds() == null || req.messageIds().isEmpty()) {
            return Response.ok(java.util.Map.of()).build();
        }
        var result = reactionService.getReactionsBatch(req.messageIds());
        return Response.ok(result).build();
    }

    @POST
    @Path("/channels/{name}/messages/{messageId}/reactions")
    public Response addReaction(@PathParam("name") String name,
                                @PathParam("messageId") Long messageId,
                                AddReactionRequest req) {
        var channel = channelService.findByName(name);
        if (channel.isEmpty()) {return Response.status(404).build();}
        if (req == null || req.emoji() == null || req.emoji().isBlank())
            return Response.status(400).entity("emoji is required").build();
        String actorId = securityIdentity.getPrincipal().getName();
        reactionService.react(messageId, req.emoji(), actorId,
                              io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID);
        return Response.ok().build();
    }

    @jakarta.ws.rs.DELETE
    @Path("/channels/{name}/messages/{messageId}/reactions")
    public Response removeReaction(@PathParam("name") String name,
                                   @PathParam("messageId") Long messageId,
                                   @QueryParam("emoji") String emoji) {
        var channel = channelService.findByName(name);
        if (channel.isEmpty()) {return Response.status(404).build();}
        if (emoji == null || emoji.isBlank())
            return Response.status(400).entity("emoji query param is required").build();
        String actorId = securityIdentity.getPrincipal().getName();
        reactionService.unreact(messageId, emoji, actorId);
        return Response.ok().build();
    }

    @GET
    @Path("/channels/{name}/topics")
    public Response topics(@PathParam("name") String name) {
        var channel = channelService.findByName(name);
        if (channel.isEmpty()) {return Response.status(404).build();}
        return Response.ok(topicService.listTopics(channel.get().id())).build();
    }

    @GET
    @Path("/channels/{name}/members")
    public Response members(@PathParam("name") String name) {
        var channel = channelService.findByName(name);
        if (channel.isEmpty()) {return Response.status(404).build();}
        return Response.ok(membershipService.listMembers(channel.get().id())).build();
    }


    record MeshConfig(String strategy, int interval, int cursorStalenessMinutes) {}

    record PostMessageRequest(String content, String type,
                              Long inReplyTo, String correlationId,
                              java.util.List<io.casehub.qhorus.api.message.ArtefactRef> artefactRefs,
                              String target, String deadline, String topic) {}
}
