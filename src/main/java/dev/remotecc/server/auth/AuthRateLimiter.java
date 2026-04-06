package dev.remotecc.server.auth;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Sliding-window rate limiter for authentication endpoints.
 * Prevents brute-force and credential-stuffing against the WebAuthn ceremony
 * and invite-token registration flow.
 */
@ApplicationScoped
public class AuthRateLimiter {

    private static final Logger LOG = Logger.getLogger(AuthRateLimiter.class);

    static final int MAX_ATTEMPTS = 10;
    static final Duration WINDOW = Duration.ofMinutes(5);

    private final ConcurrentHashMap<String, ArrayDeque<Instant>> attempts = new ConcurrentHashMap<>();

    void init(@Observes Router router) {
        for (var path : new String[]{
                "/q/webauthn/login/*",
                "/q/webauthn/register/*",
                "/auth/register"}) {
            router.route(path).order(-5).handler(this::check);
        }
    }

    private void check(RoutingContext ctx) {
        var ip = ctx.request().remoteAddress().host();
        if (isRateLimited(ip)) {
            LOG.warnf("Rate limit exceeded for IP %s on %s", ip, ctx.request().path());
            ctx.response()
                .setStatusCode(429)
                .putHeader("Content-Type", "application/json")
                .putHeader("Retry-After", String.valueOf(WINDOW.toSeconds()))
                .end("{\"error\":\"Too many requests — try again later\"}");
            return;
        }
        ctx.next();
    }

    /** Clears all recorded attempts. Package-private for testing only. */
    void resetForTest() {
        attempts.clear();
    }

    /** Returns true if the IP has exceeded the rate limit. Package-private for testing. */
    boolean isRateLimited(String ip) {
        var now = Instant.now();
        var deque = attempts.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.removeIf(t -> t.isBefore(now.minus(WINDOW)));
            deque.addLast(now);
            return deque.size() > MAX_ATTEMPTS;
        }
    }
}
