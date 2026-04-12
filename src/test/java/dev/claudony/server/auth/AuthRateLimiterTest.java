package dev.claudony.server.auth;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthRateLimiterTest {

    AuthRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new AuthRateLimiter();
    }

    @Test
    void allowsRequestsUpToLimit() {
        for (int i = 0; i < AuthRateLimiter.MAX_ATTEMPTS; i++) {
            assertFalse(rateLimiter.isRateLimited("1.2.3.4"),
                "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void blocksRequestsExceedingLimit() {
        for (int i = 0; i < AuthRateLimiter.MAX_ATTEMPTS; i++) {
            rateLimiter.isRateLimited("10.0.0.1");
        }
        assertTrue(rateLimiter.isRateLimited("10.0.0.1"),
            "Should be blocked after limit exceeded");
    }

    @Test
    void differentIPsHaveIndependentLimits() {
        for (int i = 0; i < AuthRateLimiter.MAX_ATTEMPTS; i++) {
            rateLimiter.isRateLimited("192.168.1.1");
        }
        assertFalse(rateLimiter.isRateLimited("192.168.1.2"),
            "Different IP should not be rate limited");
    }

    @Test
    void windowExpiryUnblocksIPAfterWindowPasses() {
        // Fix clock at epoch so all attempts share the same timestamp
        Instant[] time = {Instant.EPOCH};
        rateLimiter.setClockForTest(() -> time[0]);

        // Exhaust the limit
        for (int i = 0; i < AuthRateLimiter.MAX_ATTEMPTS; i++) {
            rateLimiter.isRateLimited("172.16.0.1");
        }
        assertTrue(rateLimiter.isRateLimited("172.16.0.1"), "Should be blocked");

        // Advance clock past the window — old attempts now expire
        time[0] = Instant.EPOCH.plus(AuthRateLimiter.WINDOW).plusSeconds(1);
        assertFalse(rateLimiter.isRateLimited("172.16.0.1"),
            "Should be allowed again after window expires");
    }
}
