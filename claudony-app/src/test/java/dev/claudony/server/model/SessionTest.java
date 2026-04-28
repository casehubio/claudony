package dev.claudony.server.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class SessionTest {

    @Test
    void sessionHasRequiredFields() {
        var now = Instant.now();
        var session = new Session("id-1", "myproject", "/home/user/proj",
                "claude", SessionStatus.IDLE, now, now, Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals("id-1", session.id());
        assertEquals("myproject", session.name());
        assertEquals("/home/user/proj", session.workingDir());
        assertEquals("claude", session.command());
        assertEquals(SessionStatus.IDLE, session.status());
        assertTrue(session.expiryPolicy().isEmpty());
    }

    @Test
    void withStatusReturnsCopyWithUpdatedStatusAndPreservesExpiryPolicy() {
        var now = Instant.now();
        var session = new Session("id-1", "myproject", "/home/user/proj",
                "claude", SessionStatus.IDLE, now, now, Optional.of("terminal-output"), Optional.empty(), Optional.empty());

        var updated = session.withStatus(SessionStatus.ACTIVE);

        assertEquals(SessionStatus.ACTIVE, updated.status());
        assertEquals("id-1", updated.id());
        assertEquals(Optional.of("terminal-output"), updated.expiryPolicy());
    }

    @Test
    void withLastActivePreservesExpiryPolicy() {
        var now = Instant.now();
        var session = new Session("id-1", "myproject", "/home/user/proj",
                "claude", SessionStatus.IDLE, now, now, Optional.of("status-aware"), Optional.empty(), Optional.empty());

        var updated = session.withLastActive();

        assertEquals(Optional.of("status-aware"), updated.expiryPolicy());
        assertTrue(updated.lastActive().isAfter(now) || updated.lastActive().equals(now));
    }

    @Test
    void sessionStatusValues() {
        assertNotNull(SessionStatus.valueOf("ACTIVE"));
        assertNotNull(SessionStatus.valueOf("WAITING"));
        assertNotNull(SessionStatus.valueOf("IDLE"));
    }

    @Test
    void caseIdAndRoleNameDefaultToEmpty() {
        var now = Instant.now();
        var session = new Session("id-1", "myproject", "/home/user/proj",
                "claude", SessionStatus.IDLE, now, now, Optional.empty(),
                Optional.empty(), Optional.empty());
        assertTrue(session.caseId().isEmpty());
        assertTrue(session.roleName().isEmpty());
    }

    @Test
    void withStatusPreservesCaseIdAndRoleName() {
        var now = Instant.now();
        var session = new Session("id-1", "myproject", "/home/user/proj",
                "claude", SessionStatus.IDLE, now, now, Optional.empty(),
                Optional.of("case-abc"), Optional.of("researcher"));
        var updated = session.withStatus(SessionStatus.ACTIVE);
        assertEquals(Optional.of("case-abc"), updated.caseId());
        assertEquals(Optional.of("researcher"), updated.roleName());
    }

    @Test
    void withLastActivePreservesCaseIdAndRoleName() {
        var now = Instant.now();
        var session = new Session("id-1", "myproject", "/home/user/proj",
                "claude", SessionStatus.IDLE, now, now, Optional.empty(),
                Optional.of("case-abc"), Optional.of("coder"));
        var updated = session.withLastActive();
        assertEquals(Optional.of("case-abc"), updated.caseId());
        assertEquals(Optional.of("coder"), updated.roleName());
    }
}
