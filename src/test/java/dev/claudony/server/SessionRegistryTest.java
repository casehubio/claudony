package dev.claudony.server;

import dev.claudony.server.model.Session;
import dev.claudony.server.model.SessionStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SessionRegistryTest {

    @Inject
    SessionRegistry registry;

    @BeforeEach
    void clearRegistry() {
        registry.all().forEach(s -> registry.remove(s.id()));
    }

    @Test
    void emptyRegistryHasNoSessions() {
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void registerAndFindSession() {
        var now = Instant.now();
        var session = new Session("id-1", "proj", "/tmp", "claude", SessionStatus.IDLE, now, now);
        registry.register(session);
        var found = registry.find("id-1");
        assertTrue(found.isPresent());
        assertEquals("proj", found.get().name());
    }

    @Test
    void removeSession() {
        var now = Instant.now();
        registry.register(new Session("id-2", "proj2", "/tmp", "claude", SessionStatus.IDLE, now, now));
        registry.remove("id-2");
        assertTrue(registry.find("id-2").isEmpty());
    }

    @Test
    void allReturnsAllSessions() {
        var now = Instant.now();
        registry.register(new Session("id-3", "a", "/tmp", "claude", SessionStatus.IDLE, now, now));
        registry.register(new Session("id-4", "b", "/tmp", "claude", SessionStatus.IDLE, now, now));
        assertEquals(2, registry.all().size());
    }

    @Test
    void updateSessionStatus() {
        var now = Instant.now();
        registry.register(new Session("id-5", "proj", "/tmp", "claude", SessionStatus.IDLE, now, now));
        registry.updateStatus("id-5", SessionStatus.ACTIVE);
        var updated = registry.find("id-5");
        assertTrue(updated.isPresent());
        assertEquals(SessionStatus.ACTIVE, updated.get().status());
    }
}
