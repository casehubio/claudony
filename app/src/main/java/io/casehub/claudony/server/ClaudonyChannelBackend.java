package io.casehub.claudony.server;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.BackendRegistry;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.CommitmentStateChangedEvent;
import io.casehub.qhorus.api.gateway.HumanObserverChannelBackend;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.push.QhorusWebSocketBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class ClaudonyChannelBackend implements HumanObserverChannelBackend {

    public static final String BACKEND_ID = "claudony-observer";

    @Inject
    QhorusWebSocketBroadcaster broadcaster;

    @Inject
    BackendRegistry registry;

    @Override
    public String backendId() { return BACKEND_ID; }

    @Override
    public ActorType actorType() { return ActorType.HUMAN; }

    @Override
    public void open(ChannelRef channel, Map<String, String> metadata) {}

    @Override
    public void close(ChannelRef channel) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        broadcaster.pushMessage(channel, message);
    }

    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        if (event.channelName() != null && event.channelName().startsWith("case-")) {
            registry.registerBackend(event.channelId(), this, "human_observer");
        }
    }

    void onCommitmentChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS)
                             CommitmentStateChangedEvent event) {
        broadcaster.broadcastCommitment(event.commitment());
    }
}
