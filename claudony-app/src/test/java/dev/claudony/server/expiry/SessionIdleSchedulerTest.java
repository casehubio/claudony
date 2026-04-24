package dev.claudony.server.expiry;

import dev.claudony.server.SessionRegistry;
import dev.claudony.server.TmuxService;
import dev.claudony.server.model.Session;
import dev.claudony.server.model.SessionExpiredEvent;
import dev.claudony.server.model.SessionStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SessionIdleSchedulerTest {

    @Inject SessionIdleScheduler scheduler;
    @Inject SessionRegistry registry;
    @Inject TmuxService tmux;
    @Inject EventCaptor eventCaptor;

    private static final String EXPIRED_TMUX = "test-sched-expired";
    private static final String ACTIVE_TMUX  = "test-sched-active";

    @BeforeEach
    void setUp() {
        registry.all().forEach(s -> registry.remove(s.id()));
        eventCaptor.events.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        registry.all().forEach(s -> registry.remove(s.id()));
        if (tmux.sessionExists(EXPIRED_TMUX)) tmux.killSession(EXPIRED_TMUX);
        if (tmux.sessionExists(ACTIVE_TMUX)) tmux.killSession(ACTIVE_TMUX);
    }

    @Test
    void expiredSessionIsKilledAndRemovedFromRegistry() throws Exception {
        tmux.createSession(EXPIRED_TMUX, System.getProperty("user.home"), "bash");
        var now = Instant.now();
        var session = new Session("exp-id", EXPIRED_TMUX, "/tmp", "bash",
                SessionStatus.IDLE, now, now.minus(Duration.ofDays(8)), Optional.empty());
        registry.register(session);

        scheduler.expiryCheck();

        assertFalse(tmux.sessionExists(EXPIRED_TMUX), "Expired tmux session should be killed");
        assertTrue(registry.find("exp-id").isEmpty(), "Expired registry entry should be removed");
    }

    @Test
    void activeSessionIsNotKilledOrRemoved() throws Exception {
        tmux.createSession(ACTIVE_TMUX, System.getProperty("user.home"), "bash");
        var now = Instant.now();
        var session = new Session("active-id", ACTIVE_TMUX, "/tmp", "bash",
                SessionStatus.IDLE, now, now, Optional.empty());
        registry.register(session);

        scheduler.expiryCheck();

        assertTrue(tmux.sessionExists(ACTIVE_TMUX), "Active tmux session should survive");
        assertTrue(registry.find("active-id").isPresent(), "Active registry entry should survive");
    }

    @Test
    void expiryEventFiredForExpiredSession() throws Exception {
        tmux.createSession(EXPIRED_TMUX, System.getProperty("user.home"), "bash");
        var now = Instant.now();
        var session = new Session("evt-id", EXPIRED_TMUX, "/tmp", "bash",
                SessionStatus.IDLE, now, now.minus(Duration.ofDays(8)), Optional.empty());
        registry.register(session);

        scheduler.expiryCheck();

        assertEquals(1, eventCaptor.events.size(), "SessionExpiredEvent should be fired once");
        assertEquals("evt-id", eventCaptor.events.get(0).session().id());
    }

    @Test
    void perSessionPolicyOverridesDefault() throws Exception {
        // terminal-output policy: window_activity is recent → not expired despite old lastActive
        tmux.createSession(EXPIRED_TMUX, System.getProperty("user.home"), "bash");
        Thread.sleep(300);
        var now = Instant.now();
        var session = new Session("pol-id", EXPIRED_TMUX, "/tmp", "bash",
                SessionStatus.IDLE, now, now.minus(Duration.ofDays(8)),
                Optional.of("terminal-output")); // policy override
        registry.register(session);

        scheduler.expiryCheck();

        // terminal-output uses window_activity which is recent — should NOT expire
        assertTrue(registry.find("pol-id").isPresent(),
                "Session with terminal-output policy and recent tmux activity should not be expired");
        assertTrue(tmux.sessionExists(EXPIRED_TMUX), "Tmux session should survive");
    }

    @Singleton
    static class EventCaptor {
        final List<SessionExpiredEvent> events = new ArrayList<>();
        void observe(@Observes SessionExpiredEvent e) { events.add(e); }
    }
}
