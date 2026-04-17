package dev.claudony.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.claudony.config.ClaudonyConfig;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Multi;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @GET
    @Path("/config")
    public MeshConfig config() {
        return new MeshConfig(config.meshRefreshStrategy(), config.meshRefreshInterval());
    }

    @GET
    @Path("/channels")
    public List<QhorusMcpTools.ChannelDetail> channels() {
        return qhorusMcpTools.listChannels();
    }

    @GET
    @Path("/instances")
    public List<QhorusMcpTools.InstanceInfo> instances() {
        return qhorusMcpTools.listInstances(null);
    }

    @GET
    @Path("/channels/{name}/timeline")
    public List<Map<String, Object>> timeline(
            @PathParam("name") String name,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        try {
            return qhorusMcpTools.getChannelTimeline(name, null, limit);
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
        List<QhorusMcpTools.ChannelDetail> channels = qhorusMcpTools.listChannels();
        if (channels.isEmpty()) return List.of();

        int perChannel = Math.max(5, limit / channels.size());
        List<Map<String, Object>> combined = new ArrayList<>();

        for (QhorusMcpTools.ChannelDetail ch : channels) {
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
}
