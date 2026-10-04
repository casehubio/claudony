package io.casehub.claudony.casehub.fleet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class ModelFallbackEventObserver {

    private static final Logger LOG = Logger.getLogger(ModelFallbackEventObserver.class);

    private final AtomicInteger fallbackCount = new AtomicInteger();

    void onFallback(@Observes ModelFallbackEvent event) {
        int count = fallbackCount.incrementAndGet();
        LOG.warnf("Model fallback #%d: pool=%s requested=%s resolved=%s depth=%d",
                  count, event.poolName(), event.requestedModel(),
                  event.resolvedModel(), event.fallbackDepth());
    }

    public int fallbackCount() {
        return fallbackCount.get();
    }

    public void resetForTest() {
        fallbackCount.set(0);
    }
}
