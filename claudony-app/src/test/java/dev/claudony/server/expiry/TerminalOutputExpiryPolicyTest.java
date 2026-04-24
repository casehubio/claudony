package dev.claudony.server.expiry;

import dev.claudony.server.TmuxService;
import dev.claudony.server.model.Session;
import dev.claudony.server.model.SessionStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TerminalOutputExpiryPolicyTest {

    @Inject TerminalOutputExpiryPolicy policy;
    @Inject TmuxService tmux;

    private static final String TEST_SESSION = "test-expiry-terminal-output";

    @AfterEach
    void cleanup() throws Exception {
        if (tmux.sessionExists(TEST_SESSION)) tmux.killSession(TEST_SESSION);
    }

    @Test
    void notExpiredForSessionWithRecentTmuxActivity() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "echo active");
        Thread.sleep(500);
        assertFalse(policy.isExpired(session(TEST_SESSION, Instant.now()), Duration.ofDays(7)));
    }

    @Test
    void expiredForNonExistentSession() {
        assertTrue(policy.isExpired(session("no-such-session-zzz", Instant.now()), Duration.ofDays(7)));
    }

    @Test
    void nameIsTerminalOutput() {
        assertThat(policy.name()).isEqualTo("terminal-output");
    }

    private Session session(String name, Instant lastActive) {
        var now = Instant.now();
        return new Session("id", name, "/tmp", "bash", SessionStatus.IDLE, now, lastActive, Optional.empty());
    }
}
