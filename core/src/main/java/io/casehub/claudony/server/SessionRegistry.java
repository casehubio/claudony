package io.casehub.claudony.server;

import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@ApplicationScoped
public class SessionRegistry {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final List<Consumer<String>> changeListeners = new CopyOnWriteArrayList<>();
    private final TenantContext tenantContext;

    @Inject
    public SessionRegistry(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    public void register(Session session) {
        sessions.put(session.id(), session);
    }

    public Optional<Session> find(String id) {
        String tenant = tenantContext.currentTenantId();
        return Optional.ofNullable(sessions.get(id))
                .filter(s -> s.tenancyId().equals(tenant));
    }

    public Collection<Session> all() {
        String tenant = tenantContext.currentTenantId();
        return sessions.values().stream()
                .filter(s -> s.tenancyId().equals(tenant))
                .toList();
    }

    public List<Session> findByCaseId(String caseId) {
        String tenant = tenantContext.currentTenantId();
        return sessions.values().stream()
                .filter(s -> s.tenancyId().equals(tenant))
                .filter(s -> s.caseId().map(caseId::equals).orElse(false))
                .sorted(Comparator.comparing(Session::createdAt))
                .toList();
    }

    public Optional<Session> findUnscoped(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public Collection<Session> allUnscoped() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public boolean existsByName(String name) {
        return sessions.values().stream().anyMatch(s -> s.name().equals(name));
    }

    public Session remove(String id) {
        notifyListeners(id);
        return sessions.remove(id);
    }

    public void updateStatus(String id, SessionStatus status) {
        sessions.computeIfPresent(id, (k, s) -> s.withStatus(status));
        notifyListeners(id);
    }

    public void touch(String id) {
        sessions.computeIfPresent(id, (k, s) -> s.withLastActive());
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
