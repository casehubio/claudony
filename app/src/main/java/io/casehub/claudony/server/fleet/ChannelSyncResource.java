package io.casehub.claudony.server.fleet;

import io.casehub.claudony.server.ChannelEventBus;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/internal/channels")
public class ChannelSyncResource {

    @Inject ChannelGateway gateway;
    @Inject ChannelEventBus channelEventBus;

    @POST
    @Path("/sync")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("fleet")
    public Response sync(ChannelSyncRequest request) {
        gateway.initChannel(request.channelId(),
                new ChannelRef(request.channelId(), request.channelName()));
        return Response.noContent().build();
    }

    @POST
    @Path("/notify")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("fleet")
    public Response notify(ChannelNotifyRequest request) {
        channelEventBus.emit(request.channelName());
        return Response.noContent().build();
    }
}
