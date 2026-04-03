package dev.remotecc.server;

import dev.remotecc.server.model.Session;
import dev.remotecc.server.model.SessionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
}
