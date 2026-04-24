package dev.claudony.server.expiry;

import dev.claudony.config.ClaudonyConfig;
import dev.claudony.server.SessionRegistry;
import dev.claudony.server.TmuxService;
import dev.claudony.server.model.SessionExpiredEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SessionIdleScheduler {

    private static final Logger LOG = Logger.getLogger(SessionIdleScheduler.class);

    @Inject ClaudonyConfig config;
    @Inject SessionRegistry registry;
    @Inject TmuxService tmux;
    @Inject ExpiryPolicyRegistry policyRegistry;
    @Inject Event<SessionExpiredEvent> expiryEvents;

    @Scheduled(every = "5m", delayed = "1m")
    void scheduledCheck() {
        if (!config.isServerMode()) return;
        expiryCheck();
    }

    void expiryCheck() {
        var timeout = config.sessionTimeout();
        for (var session : registry.all()) {
            var policy = policyRegistry.resolve(session.expiryPolicy().orElse(null));
            if (!policy.isExpired(session, timeout)) continue;
            LOG.infof("Expiring session '%s' (policy=%s, lastActive=%s)",
                    session.name(), policy.name(), session.lastActive());
            try {
                expiryEvents.fire(new SessionExpiredEvent(session));
                tmux.killSession(session.name());
                registry.remove(session.id());
            } catch (Exception e) {
                LOG.warnf("Could not expire session '%s': %s — registry entry preserved", session.name(), e.getMessage());
            }
        }
    }
}
