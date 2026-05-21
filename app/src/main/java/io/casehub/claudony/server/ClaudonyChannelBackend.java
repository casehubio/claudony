package io.casehub.claudony.server;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.HumanObserverChannelBackend;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Qhorus HumanObserverChannelBackend for the Claudony dashboard panel.
 *
 * <p>Singleton — registered once per channel by {@code MeshResource.channelEvents()} and
 * by {@code ServerStartup.bootstrapChannelBackends()} on restart. {@code post()} ticks
 * {@link ChannelEventBus} (reserved for future event-driven delivery); the current SSE
 * endpoint uses a 500 ms server-side tick via {@code Multi.createFrom().ticks()} instead.
 * See claudony#131 to track moving to true push delivery.
 */
@ApplicationScoped
public class ClaudonyChannelBackend implements HumanObserverChannelBackend {

    public static final String BACKEND_ID = "claudony-observer";

    private final ChannelEventBus channelEventBus;

    @Inject
    public ClaudonyChannelBackend(ChannelEventBus channelEventBus) {
        this.channelEventBus = channelEventBus;
    }

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.HUMAN; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        channelEventBus.emit(channel.name());
    }
}
