package dev.remotecc.server.auth;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InviteService {

    static final Duration TTL = Duration.ofHours(24);

    private final ConcurrentHashMap<String, Instant> tokens = new ConcurrentHashMap<>();

    public String generate() {
        var token = UUID.randomUUID().toString();
        tokens.put(token, Instant.now().plus(TTL));
        return token;
    }

    public boolean isValid(String token) {
        var expiry = tokens.get(token);
        return expiry != null && Instant.now().isBefore(expiry);
    }

    public void consume(String token) {
        tokens.remove(token);
    }

    /** Test hook — inject a token with a custom expiry. */
    void injectForTest(String token, Instant expiry) {
        tokens.put(token, expiry);
    }
}
