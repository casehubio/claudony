package io.casehub.claudony.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.claudony.config.ClaudonyConfig;
import io.casehub.claudony.server.model.SessionResponse;
import io.casehub.platform.api.mcp.HandWrittenEndpoint;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.jboss.logging.Logger;

@HandWrittenEndpoint("SSE streaming endpoint — cannot be generated")
@Path("/api/sessions")
@Authenticated
public class SessionResource {

    private static final Logger LOG = Logger.getLogger(SessionResource.class);

    @Inject SessionRegistry registry;
    @Inject SessionService sessionService;
    @Inject CaseEventBroadcaster caseEventBroadcaster;
    @Inject ClaudonyConfig config;
    @Inject ObjectMapper mapper;

    @GET
    @Path("/{id}/case-events")
    @Produces("text/event-stream")
    public Multi<String> caseEvents(@PathParam("id") String id) {
        var session = registry.find(id)
                .orElseThrow(() -> new NotFoundException("Session not found: " + id));
        String caseId = session.caseId()
                .orElseThrow(() -> new NotFoundException("Session " + id + " has no caseId"));
        return caseEventBroadcaster.subscribe(caseId, () -> buildCaseSnapshot(caseId));
    }

    private String buildCaseSnapshot(String caseId) {
        try {
            var workers = registry.findByCaseId(caseId).stream()
                    .map(s -> SessionResponse.from(s, config.port(), sessionService.resolvedPolicy(s)))
                    .toList();
            return mapper.writeValueAsString(workers);
        } catch (JsonProcessingException e) {
            LOG.warnf("buildCaseSnapshot serialization error for caseId=%s: %s", caseId, e.getMessage());
            return "[]";
        }
    }
}
