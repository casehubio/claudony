package io.casehub.claudony.server.push;

import io.casehub.claudony.casehub.fleet.PoolEventEmitter;
import io.casehub.pages.push.EventBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class PoolEventEmitterProducer {

    @Inject
    EventBroadcaster eventBroadcaster;

    @Produces
    @ApplicationScoped
    PoolEventEmitter poolEventEmitter() {
        return new PoolEventEmitter(eventBroadcaster::broadcast);
    }
}
