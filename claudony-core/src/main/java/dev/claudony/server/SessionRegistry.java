package dev.claudony.server;

import dev.claudony.server.model.Session;
import dev.claudony.server.model.SessionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class SessionRegistry {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public void register(Session session) {
        sessions.put(session.id(), session);
    }

    public Optional<Session> find(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public Collection<Session> all() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public void remove(String id) {
        sessions.remove(id);
    }

    public void updateStatus(String id, SessionStatus status) {
        sessions.computeIfPresent(id, (k, s) -> s.withStatus(status));
    }

    public void touch(String id) {
        sessions.computeIfPresent(id, (k, s) -> s.withLastActive());
    }

    public List<Session> findByCaseId(String caseId) {
        return sessions.values().stream()
                .filter(s -> s.caseId().map(caseId::equals).orElse(false))
                .sorted(Comparator.comparing(Session::createdAt))
                .toList();
    }
}
