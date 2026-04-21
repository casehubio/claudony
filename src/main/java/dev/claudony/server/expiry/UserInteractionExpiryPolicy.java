package dev.claudony.server.expiry;

import dev.claudony.server.model.Session;
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
