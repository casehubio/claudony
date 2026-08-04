package io.casehub.claudony.casehub.inbox;

import io.casehub.claudony.casehub.WorkerSessionMapping;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TenantContext;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CommitmentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActionAggregationServiceTest {

    CommitmentStore commitmentStore = mock(CommitmentStore.class);
    SessionRegistry sessionRegistry;
    StallTracker stallTracker = new StallTracker();
    WorkerSessionMapping sessionMapping = mock(WorkerSessionMapping.class);
    TenantContext tenantContext = mock(TenantContext.class);
    ActionAggregationService service;

    @BeforeEach
    void setUp() {
        when(tenantContext.currentTenantId()).thenReturn("default");
        sessionRegistry = new SessionRegistry(tenantContext);
        when(commitmentStore.findAllOpen()).thenReturn(List.of());
        service = new ActionAggregationService(commitmentStore, sessionRegistry, stallTracker, sessionMapping);
    }

    @Test
    void emptyWhenNoSources() {
        var result = service.listActions();
        assertTrue(result.items().isEmpty());
        assertEquals(0, result.counts().high());
    }

    @Test
    void commitmentMappedToActionItem() {
        var commitment = Commitment.builder()
                .id(UUID.randomUUID())
                .correlationId("corr-1")
                .channelId(UUID.randomUUID())
                .messageType(MessageType.COMMAND)
                .requester("agent-1")
                .obligor("human-1")
                .state(CommitmentState.OPEN)
                .createdAt(Instant.now())
                .build();
        when(commitmentStore.findAllOpen()).thenReturn(List.of(commitment));

        var result = service.listActions();
        assertEquals(1, result.items().size());
        assertEquals(SourceType.COMMITMENT, result.items().get(0).sourceType());
        assertEquals(Urgency.LOW, result.items().get(0).urgency());
        assertTrue(result.items().get(0).actionable());
    }

    @Test
    void overdueCommitmentIsHighUrgency() {
        var commitment = Commitment.builder()
                .id(UUID.randomUUID())
                .correlationId("corr-1")
                .channelId(UUID.randomUUID())
                .messageType(MessageType.COMMAND)
                .requester("agent-1")
                .state(CommitmentState.OPEN)
                .expiresAt(Instant.now().minusSeconds(3600))
                .createdAt(Instant.now().minusSeconds(7200))
                .build();
        when(commitmentStore.findAllOpen()).thenReturn(List.of(commitment));

        var result = service.listActions();
        assertEquals(Urgency.HIGH, result.items().get(0).urgency());
        assertEquals(1, result.counts().high());
    }

    @Test
    void nearDeadlineCommitmentIsMediumUrgency() {
        var commitment = Commitment.builder()
                .id(UUID.randomUUID())
                .correlationId("corr-1")
                .channelId(UUID.randomUUID())
                .messageType(MessageType.COMMAND)
                .requester("agent-1")
                .state(CommitmentState.OPEN)
                .expiresAt(Instant.now().plusSeconds(1800))
                .createdAt(Instant.now())
                .build();
        when(commitmentStore.findAllOpen()).thenReturn(List.of(commitment));

        var result = service.listActions();
        assertEquals(Urgency.MEDIUM, result.items().get(0).urgency());
    }

    @Test
    void stalledWorkerMappedToHighUrgency() {
        var caseId = UUID.randomUUID();
        var session = new Session("s1", "worker-stalled", "/work", "claude",
                SessionStatus.ACTIVE, Instant.now(), Instant.now(), Optional.empty(),
                Optional.of(caseId.toString()), Optional.of("reviewer"), "default");
        sessionRegistry.register(session);
        stallTracker.markStalled("reviewer");
        when(sessionMapping.findByRole("reviewer")).thenReturn(Optional.of("s1"));

        var result = service.listActions();
        var stalls = result.items().stream()
                .filter(a -> a.sourceType() == SourceType.STALL)
                .toList();
        assertEquals(1, stalls.size());
        assertEquals(Urgency.HIGH, stalls.get(0).urgency());
        assertEquals(caseId, stalls.get(0).caseId());
    }

    @Test
    void sortedByUrgencyThenCreatedAt() {
        var oldCommitment = Commitment.builder()
                .id(UUID.randomUUID()).correlationId("c1").channelId(UUID.randomUUID())
                .messageType(MessageType.COMMAND).requester("a").state(CommitmentState.OPEN)
                .createdAt(Instant.now().minusSeconds(100)).build();
        when(commitmentStore.findAllOpen()).thenReturn(List.of(oldCommitment));

        var caseId = UUID.randomUUID();
        stallTracker.markStalled("stalled-role");
        var stalledSession = new Session("s2", "stalled", "/work", "claude",
                SessionStatus.ACTIVE, Instant.now(), Instant.now(), Optional.empty(),
                Optional.of(caseId.toString()), Optional.of("stalled-role"), "default");
        sessionRegistry.register(stalledSession);
        when(sessionMapping.findByRole("stalled-role")).thenReturn(Optional.of("s2"));

        var result = service.listActions();
        assertTrue(result.items().size() >= 2);
        assertEquals(SourceType.STALL, result.items().get(0).sourceType());
    }

    @Test
    void stalledWorkerWithNoSessionSkipped() {
        stallTracker.markStalled("ghost-role");
        when(sessionMapping.findByRole("ghost-role")).thenReturn(Optional.empty());

        var result = service.listActions();
        assertTrue(result.items().stream().noneMatch(a -> a.sourceType() == SourceType.STALL));
    }
}
