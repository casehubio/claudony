package io.casehub.claudony.server;

import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import io.casehub.platform.api.identity.TenancyConstants;

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
        var session = new Session("id-1", "proj", "/tmp", "claude", SessionStatus.IDLE, now, now, Optional.empty(), Optional.empty(), Optional.empty(), TenancyConstants.DEFAULT_TENANT_ID);
        registry.register(session);
        var found = registry.find("id-1");
        assertTrue(found.isPresent());
        assertEquals("proj", found.get().name());
    }

    @Test
    void removeSession() {
        var now = Instant.now();
        registry.register(new Session("id-2", "proj2", "/tmp", "claude", SessionStatus.IDLE, now, now, Optional.empty(), Optional.empty(), Optional.empty(), TenancyConstants.DEFAULT_TENANT_ID));
        registry.remove("id-2");
        assertTrue(registry.find("id-2").isEmpty());
    }

    @Test
    void allReturnsAllSessions() {
        var now = Instant.now();
        registry.register(new Session("id-3", "a", "/tmp", "claude", SessionStatus.IDLE, now, now, Optional.empty(), Optional.empty(), Optional.empty(), TenancyConstants.DEFAULT_TENANT_ID));
        registry.register(new Session("id-4", "b", "/tmp", "claude", SessionStatus.IDLE, now, now, Optional.empty(), Optional.empty(), Optional.empty(), TenancyConstants.DEFAULT_TENANT_ID));
        assertEquals(2, registry.all().size());
    }

    @Test
    void updateSessionStatus() {
        var now = Instant.now();
        registry.register(new Session("id-5", "proj", "/tmp", "claude", SessionStatus.IDLE, now, now, Optional.empty(), Optional.empty(), Optional.empty(), TenancyConstants.DEFAULT_TENANT_ID));
        registry.updateStatus("id-5", SessionStatus.ACTIVE);
        var updated = registry.find("id-5");
        assertTrue(updated.isPresent());
        assertEquals(SessionStatus.ACTIVE, updated.get().status());
    }

    @Test
    void touchUpdatesLastActive() throws InterruptedException {
        var past = Instant.now().minusSeconds(60);
        registry.register(new Session("id-touch", "proj", "/tmp", "claude",
                SessionStatus.IDLE, past, past, Optional.empty(), Optional.empty(), Optional.empty(), TenancyConstants.DEFAULT_TENANT_ID));

        Thread.sleep(10);
        registry.touch("id-touch");

        var updated = registry.find("id-touch");
        assertTrue(updated.isPresent());
        assertTrue(updated.get().lastActive().isAfter(past),
                "touch() should update lastActive beyond the original value");
    }

    @Test
    void touchOnUnknownIdIsNoOp() {
        registry.touch("nonexistent-id"); // must not throw
    }

    @Test
    void findByCaseId_returnsSessionsWithMatchingCaseId() {
        var now = Instant.now();
        var s1 = new Session("s1", "w1", "/tmp", "claude", SessionStatus.IDLE,
                now.minusSeconds(10), now.minusSeconds(10), Optional.empty(),
                Optional.of("case-x"), Optional.of("agent"), TenancyConstants.DEFAULT_TENANT_ID);
        var s2 = new Session("s2", "w2", "/tmp", "claude", SessionStatus.ACTIVE,
                now, now, Optional.empty(),
                Optional.of("case-x"), Optional.of("coder"), TenancyConstants.DEFAULT_TENANT_ID);
        var s3 = new Session("s3", "w3", "/tmp", "claude", SessionStatus.IDLE,
                now, now, Optional.empty(),
                Optional.of("case-y"), Optional.of("reviewer"), TenancyConstants.DEFAULT_TENANT_ID);
        registry.register(s1);
        registry.register(s2);
        registry.register(s3);

        var result = registry.findByCaseId("case-x");
        assertThat(result).hasSize(2)
                .extracting(Session::id)
                .containsExactly("s1", "s2"); // ordered by createdAt
    }

    @Test
    void findByCaseId_returnsEmptyForUnknownCaseId() {
        assertTrue(registry.findByCaseId("nonexistent-case").isEmpty());
    }

    @Test
    void findByCaseId_excludesSessionsWithNoCaseId() {
        var now = Instant.now();
        var standalone = new Session("s-alone", "w1", "/tmp", "claude", SessionStatus.IDLE,
                now, now, Optional.empty(), Optional.empty(), Optional.empty(), TenancyConstants.DEFAULT_TENANT_ID);
        registry.register(standalone);
        assertTrue(registry.findByCaseId("case-z").isEmpty());
    }
}
