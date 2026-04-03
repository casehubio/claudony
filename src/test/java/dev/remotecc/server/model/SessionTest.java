package dev.remotecc.server.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class SessionTest {

    @Test
    void sessionHasRequiredFields() {
        var now = Instant.now();
        var session = new Session("id-1", "myproject", "/home/user/proj",
                "claude", SessionStatus.IDLE, now, now);

        assertEquals("id-1", session.id());
        assertEquals("myproject", session.name());
        assertEquals("/home/user/proj", session.workingDir());
        assertEquals("claude", session.command());
        assertEquals(SessionStatus.IDLE, session.status());
    }

    @Test
    void withStatusReturnsCopyWithUpdatedStatus() {
        var now = Instant.now();
        var session = new Session("id-1", "myproject", "/home/user/proj",
                "claude", SessionStatus.IDLE, now, now);

        var updated = session.withStatus(SessionStatus.ACTIVE);

        assertEquals(SessionStatus.ACTIVE, updated.status());
        assertEquals("id-1", updated.id());
    }

    @Test
    void sessionStatusValues() {
        assertNotNull(SessionStatus.valueOf("ACTIVE"));
        assertNotNull(SessionStatus.valueOf("WAITING"));
        assertNotNull(SessionStatus.valueOf("IDLE"));
    }
}
