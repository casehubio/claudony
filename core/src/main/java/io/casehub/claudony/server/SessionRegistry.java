package io.casehub.claudony.server;

import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

@ApplicationScoped
public class SessionRegistry {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final List<Consumer<String>> changeListeners = new CopyOnWriteArrayList<>();

    public void register(Session session) {
        sessions.put(session.id(), session);
    }

    public Optional<Session> find(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public Collection<Session> all() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public Session remove(String id) {
        notifyListeners(id);
        return sessions.remove(id);
    }

    public void updateStatus(String id, SessionStatus status) {
        sessions.computeIfPresent(id, (k, s) -> s.withStatus(status));
        // notifyListeners is a no-op if id is absent (null guard in the helper)
        notifyListeners(id);
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

    public void addChangeListener(Consumer<String> listener) {
        changeListeners.add(listener);
    }

    private void notifyListeners(String sessionId) {
        var session = sessions.get(sessionId);
        if (session != null) {
            session.caseId().ifPresent(caseId ->
                    changeListeners.forEach(l -> l.accept(caseId)));
        }
    }
}
