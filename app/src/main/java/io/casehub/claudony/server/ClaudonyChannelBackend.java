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
 * <p>Singleton — one instance registered per channel. {@code post()} ticks
 * the {@link ChannelEventBus} so active SSE subscribers fetch and render
 * new messages via {@code QhorusDashboardService.getTimeline()}.
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
