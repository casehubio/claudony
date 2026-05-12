package io.casehub.claudony.server.expiry;

import io.casehub.claudony.server.model.Session;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class UserInteractionExpiryPolicy implements ExpiryPolicy {

    @Override
    public String name() { return "user-interaction"; }

    @Override
    public boolean isExpired(Session session, Duration timeout) {
        return Duration.between(session.lastActive(), Instant.now()).compareTo(timeout) > 0;
    }
}
