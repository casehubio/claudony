package io.casehub.claudony.casehub.inbox;

import io.casehub.claudony.casehub.WorkerSessionMapping;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.model.Session;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.store.CommitmentStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ActionAggregationService {

    private final CommitmentStore commitmentStore;
    private final SessionRegistry sessionRegistry;
    private final StallTracker stallTracker;
    private final WorkerSessionMapping sessionMapping;

    @Inject
    public ActionAggregationService(CommitmentStore commitmentStore,
                                     SessionRegistry sessionRegistry,
                                     StallTracker stallTracker,
                                     WorkerSessionMapping sessionMapping) {
        this.commitmentStore = commitmentStore;
        this.sessionRegistry = sessionRegistry;
        this.stallTracker = stallTracker;
        this.sessionMapping = sessionMapping;
    }

    public ActionInboxResponse listActions() {
        List<ActionItem> items = new ArrayList<>();
        items.addAll(commitmentItems());
        items.addAll(stallItems());
        items.sort(Comparator.comparing(ActionItem::urgency)
                .thenComparing(ActionItem::createdAt, Comparator.reverseOrder()));

        long high = items.stream().filter(a -> a.urgency() == Urgency.HIGH).count();
        long medium = items.stream().filter(a -> a.urgency() == Urgency.MEDIUM).count();
        long low = items.stream().filter(a -> a.urgency() == Urgency.LOW).count();
        return new ActionInboxResponse(items, new ActionCounts((int) high, (int) medium, (int) low));
    }

    private List<ActionItem> commitmentItems() {
        return commitmentStore.findAllOpen().stream()
                .map(this::toCommitmentAction)
                .toList();
    }

    private ActionItem toCommitmentAction(Commitment c) {
        Urgency urgency = commitmentUrgency(c);
        return new ActionItem(
                "commitment:" + c.id(),
                SourceType.COMMITMENT,
                urgency,
                c.messageType().name() + " from " + c.requester(),
                c.state().name(),
                c.state().isActive(),
                null,
                null,
                c.createdAt() != null ? c.createdAt() : Instant.now(),
                List.of(
                        new ActionDescriptor("accept", "Accept", "POST",
                                "/api/actions/commitment:" + c.id() + "/execute/accept"),
                        new ActionDescriptor("decline", "Decline", "POST",
                                "/api/actions/commitment:" + c.id() + "/execute/decline")
                )
        );
    }

    private Urgency commitmentUrgency(Commitment c) {
        if (c.expiresAt() == null) return Urgency.LOW;
        Instant now = Instant.now();
        if (c.expiresAt().isBefore(now)) return Urgency.HIGH;
        if (Duration.between(now, c.expiresAt()).toMinutes() < 60) return Urgency.MEDIUM;
        return Urgency.LOW;
    }

    private List<ActionItem> stallItems() {
        List<ActionItem> items = new ArrayList<>();
        for (String workerId : stallTracker.stalledWorkerIds()) {
            sessionMapping.findByRole(workerId)
                    .flatMap(sessionRegistry::findUnscoped)
                    .ifPresent(session -> items.add(toStallAction(session, workerId)));
        }
        return items;
    }

    private ActionItem toStallAction(Session session, String workerId) {
        UUID caseId = session.caseId().map(UUID::fromString).orElse(null);
        return new ActionItem(
                "stall:" + session.id(),
                SourceType.STALL,
                Urgency.HIGH,
                "Worker stalled: " + workerId,
                "STALLED",
                true,
                caseId,
                null,
                session.lastActive(),
                List.of(
                        new ActionDescriptor("view", "View Terminal", "GET",
                                "/app/session.html?id=" + session.id()),
                        new ActionDescriptor("interjection", "Send Interjection", "POST",
                                "/api/actions/stall:" + session.id() + "/execute/interjection")
                )
        );
    }
}
