package io.casehub.claudony.server;

import io.casehub.platform.api.mcp.HandWrittenEndpoint;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;

@HandWrittenEndpoint("Complex validation logic — cannot be generated")
@Path("/api/mesh")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class MeshMessageResource {

    private static final Set<MessageType> VALID_HUMAN_TYPES = Set.of(
            MessageType.QUERY, MessageType.COMMAND, MessageType.RESPONSE,
            MessageType.STATUS, MessageType.DECLINE, MessageType.HANDOFF,
            MessageType.DONE, MessageType.EVENT);

    @Inject Instance<QhorusDashboardService> dashboard;
    @Inject SecurityIdentity securityIdentity;

    @POST
    @Path("/channels/{name}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response postMessage(@PathParam("name") String name, PostMessageRequest req) {
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
                    return Response.status(400).entity(type.name() + " requires inReplyTo").build();
                }
                if (req.correlationId() == null || req.correlationId().isBlank()) {
                    return Response.status(400).entity(type.name() + " requires correlationId").build();
                }
            }
            case HANDOFF -> {
                if (req.inReplyTo() == null) {
                    return Response.status(400).entity("HANDOFF requires inReplyTo").build();
                }
                if (req.correlationId() == null || req.correlationId().isBlank()) {
                    return Response.status(400).entity("HANDOFF requires correlationId").build();
                }
                if (req.target() == null || req.target().isBlank()) {
                    return Response.status(400).entity("HANDOFF requires target").build();
                }
            }
            default -> {}
        }

        boolean contentRequired = type != MessageType.EVENT;
        if (contentRequired && (req == null || req.content() == null || req.content().isBlank())) {
            return Response.status(400).entity("content must not be blank").build();
        }
        String content = (req != null && req.content() != null && !req.content().isBlank()) ? req.content() : null;
        String sender = "human:" + securityIdentity.getPrincipal().getName();

        java.time.Instant deadline = null;
        if (req != null && req.deadline() != null && !req.deadline().isBlank()) {
            try {
                deadline = java.time.Instant.parse(req.deadline());
            } catch (java.time.format.DateTimeParseException e) {
                return Response.status(400).entity("invalid deadline format").build();
            }
        }

        if (!dashboard.isResolvable()) {
            return Response.status(503).entity("Message service unavailable").build();
        }
        try {
            var result = dashboard.get().sendHumanMessage(name, sender, type, content,
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

    public record PostMessageRequest(String content, String type,
                                     Long inReplyTo, String correlationId,
                                     List<io.casehub.qhorus.api.message.ArtefactRef> artefactRefs,
                                     String target, String deadline, String topic) {}
}
