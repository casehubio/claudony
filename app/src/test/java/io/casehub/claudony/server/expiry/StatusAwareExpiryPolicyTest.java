package io.casehub.claudony.server.expiry;

import io.casehub.claudony.Await;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import io.casehub.platform.api.identity.TenancyConstants;

@QuarkusTest
class StatusAwareExpiryPolicyTest {

    @Inject
           StatusAwareExpiryPolicy policy;
    @Inject TmuxService             tmux;

    private static final String TEST_SESSION = "test-expiry-status-aware";

    @AfterEach
    void cleanup() throws Exception {
        if (tmux.sessionExists(TEST_SESSION)) tmux.killSession(TEST_SESSION);
    }

    @Test
    void neverExpiresWhenNonShellCommandRunning() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "sleep 60");
        // wait until sleep is the foreground process (zsh startup can be slow)
        Await.until(() -> {
            try {
                return "sleep".equals(tmux.displayMessage(TEST_SESSION, "#{pane_current_command}"));
            } catch (Exception e) { return false; }
        }, Duration.ofSeconds(10), "sleep to become foreground command");
        var veryOldLastActive = Instant.now().minus(Duration.ofDays(30));
        assertFalse(policy.isExpired(session(TEST_SESSION, veryOldLastActive), Duration.ofMinutes(1)));
    }

    @Test
    void expiresAtShellPromptWhenLastActiveIsOld() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "true");
        // Wait until 'true' exits and the shell takes over — avoids a race where
        // pane_current_command is still 'true', making isExpired() return false incorrectly.
        Await.until(() -> {
            try {
                var cmd = tmux.displayMessage(TEST_SESSION, "#{pane_current_command}").trim();
                return !cmd.isBlank() && !"true".equals(cmd);
            } catch (Exception e) { return false; }
        }, Duration.ofSeconds(10), "shell to take over after 'true' exits");
        var oldLastActive = Instant.now().minus(Duration.ofDays(8));
        assertTrue(policy.isExpired(session(TEST_SESSION, oldLastActive), Duration.ofDays(7)));
    }

    @Test
    void notExpiredAtShellPromptWhenLastActiveIsRecent() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "true");
        Await.until(() -> {
            try {
                var cmd = tmux.displayMessage(TEST_SESSION, "#{pane_current_command}").trim();
                return !cmd.isBlank() && !"true".equals(cmd);
            } catch (Exception e) { return false; }
        }, Duration.ofSeconds(10), "shell to take over after 'true' exits");
        var recentLastActive = Instant.now().minus(Duration.ofHours(1));
        assertFalse(policy.isExpired(session(TEST_SESSION, recentLastActive), Duration.ofDays(7)));
    }

    @Test
    void expiredForNonExistentSession() {
        assertTrue(policy.isExpired(session("no-such-session-zzz", Instant.now()), Duration.ofDays(7)));
    }

    @Test
    void nameIsStatusAware() {
        assertThat(policy.name()).isEqualTo("status-aware");
    }

    private Session session(String name, Instant lastActive) {
        var now = Instant.now();
        return new Session("id", name, "/tmp", "bash", SessionStatus.IDLE, now, lastActive, Optional.empty(), Optional.empty(), Optional.empty(), TenancyConstants.DEFAULT_TENANT_ID);
    }
}
