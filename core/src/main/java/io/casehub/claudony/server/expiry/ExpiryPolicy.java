package io.casehub.claudony.server.expiry;

import io.casehub.claudony.server.model.Session;
import java.time.Duration;

public interface ExpiryPolicy {
    String name();
    boolean isExpired(Session session, Duration timeout);
}
