package dev.claudony.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.claudony.config.ClaudonyConfig;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Multi;
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
}
