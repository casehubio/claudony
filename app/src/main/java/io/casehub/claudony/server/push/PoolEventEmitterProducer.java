package io.casehub.claudony.server.push;

import io.casehub.claudony.casehub.fleet.PoolEventEmitter;
import io.casehub.claudony.server.fleet.PoolEventBus;
import io.casehub.pages.push.EventBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class PoolEventEmitterProducer {

    @Inject
    EventBroadcaster eventBroadcaster;

    @Inject
    PoolEventBus poolEventBus;

    @Produces
    @ApplicationScoped
    PoolEventEmitter poolEventEmitter() {
        return new PoolEventEmitter(eventBroadcaster::broadcast, poolEventBus::emit);
    }
}
