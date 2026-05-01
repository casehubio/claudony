package dev.claudony.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.claudony.config.ClaudonyConfig;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.quarkiverse.mcp.server.ToolCallException;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/api/mesh")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class MeshResource {

    private static final Logger LOG = Logger.getLogger(MeshResource.class);

    @Inject ClaudonyConfig config;
    // Used by channels/instances/feed/events endpoints added in Tasks 3–5
    @Inject QhorusMcpTools qhorusMcpTools;
    @Inject ObjectMapper mapper;

    record MeshConfig(String strategy, int interval) {}

    private static final Set<String> VALID_HUMAN_TYPES =
            Set.of("query", "command", "response", "status", "decline", "handoff", "done", "event");

    record PostMessageRequest(String content, String type) {}

    @GET
    @Path("/config")
    public MeshConfig config() {
        return new MeshConfig(config.meshRefreshStrategy(), config.meshRefreshInterval());
    }

    @GET
    @Path("/channels")
    public List<QhorusMcpToolsBase.ChannelDetail> channels() {
        return qhorusMcpTools.listChannels();
    }

    @GET
    @Path("/instances")
    public List<QhorusMcpToolsBase.InstanceInfo> instances() {
        return qhorusMcpTools.listInstances(null);
    }

    @GET
    @Path("/channels/{name}/timeline")
    public List<Map<String, Object>> timeline(
            @PathParam("name") String name,
            @QueryParam("after") Long after,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        try {
            return qhorusMcpTools.getChannelTimeline(name, after, limit);
        } catch (IllegalArgumentException | ToolCallException e) {
            // QhorusMcpTools @WrapBusinessError wraps IllegalArgumentException (unknown channel)
            // and IllegalStateException (paused/ACL-blocked) into ToolCallException before
            // they exit the CDI proxy — catch both so unknown channels return [] not 500.
            return List.of();
        }
    }

    @GET
    @Path("/feed")
    public List<Map<String, Object>> feed(
            @QueryParam("limit") @DefaultValue("100") int limit) {
        List<QhorusMcpToolsBase.ChannelDetail> channels = qhorusMcpTools.listChannels();
        if (channels.isEmpty()) return List.of();

        int perChannel = Math.max(5, limit / channels.size());
        List<Map<String, Object>> combined = new ArrayList<>();

        for (QhorusMcpToolsBase.ChannelDetail ch : channels) {
            try {
                List<Map<String, Object>> msgs = qhorusMcpTools.getChannelTimeline(
                        ch.name(), null, perChannel);
                for (Map<String, Object> m : msgs) {
                    Map<String, Object> tagged = new HashMap<>(m);
                    tagged.put("channel", ch.name());
                    combined.add(tagged);
                }
            } catch (Exception e) {
                LOG.debugf("Skipping channel %s in feed: %s", ch.name(), e.getMessage());
            }
        }

        // Sort newest-last; ISO-8601 strings sort lexicographically
        combined.sort((a, b) -> {
            String ta = String.valueOf(a.getOrDefault("created_at", ""));
            String tb = String.valueOf(b.getOrDefault("created_at", ""));
            return ta.compareTo(tb);
        });

        return combined.size() > limit ? combined.subList(0, limit) : combined;
    }

    @GET
    @Path("/events")
    @Produces("text/event-stream")
    public Multi<String> events() {
        // Pushes a full mesh snapshot every refresh-interval milliseconds.
        // Default strategy is poll; SSE is for deployments that prefer push.
        // Snapshot building is dispatched to a worker thread — JPA operations
        // (ledger writes, channel queries) are blocking and cannot run on the Vert.x I/O thread.
        long intervalMs = config.meshRefreshInterval();
        return Multi.createFrom().ticks().every(Duration.ofMillis(intervalMs))
                .onItem().transformToUniAndConcatenate(tick ->
                    Uni.createFrom().item(() -> {
                        try {
                            var snapshot = Map.of(
                                    "channels", qhorusMcpTools.listChannels(),
                                    "instances", qhorusMcpTools.listInstances(null),
                                    "feed", feed(100));
                            return "data: " + mapper.writeValueAsString(snapshot) + "\n\n";
                        } catch (Exception e) {
                            LOG.debugf("SSE snapshot error: %s", e.getMessage());
                            return "data: {}\n\n";
                        }
                    }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                );
    }

    @POST
    @Path("/channels/{name}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response postMessage(
            @PathParam("name") String name,
            PostMessageRequest req) {
        if (req == null || req.content() == null || req.content().isBlank()) {
            return Response.status(400).entity("content must not be blank").build();
        }
        String type = req.type() == null ? "status" : req.type().toLowerCase();
        if (!VALID_HUMAN_TYPES.contains(type)) {
            return Response.status(400).entity("invalid type: " + type).build();
        }
        try {
            QhorusMcpToolsBase.MessageResult result =
                    qhorusMcpTools.sendMessage(name, "human", type, req.content(), null, null, null, null, null);
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            // Not wrapped — returned directly (shouldn't happen due to @WrapBusinessError, but guard)
            return Response.status(404).entity(e.getMessage()).build();
        } catch (ToolCallException e) {
            // @WrapBusinessError wraps using ToolCallException(Throwable cause) — check cause type
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException) {
                return Response.status(404).entity(cause.getMessage()).build();
            }
            String msg = cause != null ? cause.getMessage() : e.getMessage();
            return Response.status(409).entity(msg).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(e.getMessage()).build();
        }
    }
}
