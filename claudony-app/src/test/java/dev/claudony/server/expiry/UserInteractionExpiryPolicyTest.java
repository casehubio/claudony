package dev.claudony.server.expiry;

import dev.claudony.server.model.Session;
import dev.claudony.server.model.SessionStatus;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class UserInteractionExpiryPolicyTest {

    final UserInteractionExpiryPolicy policy = new UserInteractionExpiryPolicy();

    @Test
    void notExpiredWhenLastActiveIsRecent() {
        var session = sessionWith(Instant.now().minus(Duration.ofHours(1)));
        assertFalse(policy.isExpired(session, Duration.ofDays(7)));
    }

    @Test
    void expiredWhenLastActiveIsBeyondTimeout() {
        var session = sessionWith(Instant.now().minus(Duration.ofDays(8)));
        assertTrue(policy.isExpired(session, Duration.ofDays(7)));
    }

    @Test
    void notExpiredAtExactBoundary() {
        var session = sessionWith(Instant.now().minus(Duration.ofDays(7)).plusSeconds(1));
        assertFalse(policy.isExpired(session, Duration.ofDays(7)));
    }

    @Test
    void expiredWithShortTimeout() {
        var session = sessionWith(Instant.now().minus(Duration.ofMinutes(10)));
        assertTrue(policy.isExpired(session, Duration.ofMinutes(5)));
    }

    @Test
    void nameIsUserInteraction() {
        assertThat(policy.name()).isEqualTo("user-interaction");
    }

    private Session sessionWith(Instant lastActive) {
        var now = Instant.now();
        return new Session("id", "name", "/tmp", "claude",
                SessionStatus.IDLE, now, lastActive, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
