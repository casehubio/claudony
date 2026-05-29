package io.casehub.claudony.server;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.HumanObserverChannelBackend;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Qhorus HumanObserverChannelBackend for the Claudony dashboard panel.
 *
 * <p>Self-registers for {@code case-*} channels via {@link ChannelInitialisedEvent}, which fires
 * on every {@code gateway.initChannel()} call — at startup (all persisted channels) and when new
 * channels are created. The deregister-then-register pattern is idempotent and safe for concurrent
 * restarts. {@code post()} ticks {@link ChannelEventBus} to drive SSE delivery.
 */
@ApplicationScoped
public class ClaudonyChannelBackend implements HumanObserverChannelBackend {

    public static final String BACKEND_ID = "claudony-observer";

    private final ChannelEventBus channelEventBus;
    private final ChannelGateway gateway;

    @Inject
    public ClaudonyChannelBackend(ChannelEventBus channelEventBus, ChannelGateway gateway) {
        this.channelEventBus = channelEventBus;
        this.gateway = gateway;
    }

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.HUMAN; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        channelEventBus.emit(channel.name());
    }

    // initChannel() fires on every call, including repeated calls for the same channel.
    // deregister-then-register is idempotent and safe for concurrent restarts.
    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        if (!event.channelName().startsWith("case-")) return;
        gateway.deregisterBackend(event.channelId(), BACKEND_ID);
        gateway.registerBackend(event.channelId(), this, "human_observer");
    }
}
