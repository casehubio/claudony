package dev.claudony.server;

import dev.claudony.server.model.Session;
import dev.claudony.server.model.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRegistryTest {

    private SessionRegistry registry;

    @BeforeEach
    void setUp() { registry = new SessionRegistry(); }

    private Session session(String id, String caseId) {
        return new Session(id, "name-" + id, "/tmp", "cmd", SessionStatus.IDLE,
                Instant.now(), Instant.now(), Optional.empty(),
                Optional.ofNullable(caseId), Optional.empty());
    }

    @Test
    void addChangeListener_notifiedOnStatusUpdate() {
        List<String> notified = new ArrayList<>();
        registry.addChangeListener(notified::add);
        registry.register(session("s1", "case-1"));

        registry.updateStatus("s1", SessionStatus.ACTIVE);

        assertThat(notified).containsExactly("case-1");
    }

    @Test
    void addChangeListener_notifiedOnRemove() {
        List<String> notified = new ArrayList<>();
        registry.addChangeListener(notified::add);
        registry.register(session("s2", "case-2"));

        registry.remove("s2");

        assertThat(notified).containsExactly("case-2");
    }

    @Test
    void addChangeListener_notNotified_forStandaloneSession() {
        List<String> notified = new ArrayList<>();
        registry.addChangeListener(notified::add);
        registry.register(session("s3", null));

        registry.updateStatus("s3", SessionStatus.ACTIVE);
        registry.remove("s3");

        assertThat(notified).isEmpty();
    }

    @Test
    void multipleListeners_allNotified() {
        List<String> l1 = new ArrayList<>(), l2 = new ArrayList<>();
        registry.addChangeListener(l1::add);
        registry.addChangeListener(l2::add);
        registry.register(session("s4", "case-4"));

        registry.updateStatus("s4", SessionStatus.ACTIVE);

        assertThat(l1).containsExactly("case-4");
        assertThat(l2).containsExactly("case-4");
    }
}
