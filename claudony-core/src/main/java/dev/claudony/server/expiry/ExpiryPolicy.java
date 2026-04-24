package dev.claudony.server.expiry;

import dev.claudony.server.model.Session;
import java.time.Duration;

public interface ExpiryPolicy {
    String name();
    boolean isExpired(Session session, Duration timeout);
}
