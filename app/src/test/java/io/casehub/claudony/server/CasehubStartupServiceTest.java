package io.casehub.claudony.server;

import io.casehub.claudony.casehub.ClaudonyReactiveWorkerProvisioner;
import io.casehub.claudony.casehub.ClaudonyWorkerExecutionManager;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CasehubStartupServiceTest {

    private SessionRegistry registry;
    private CrossTenantCaseInstanceRepository caseInstanceRepo;
    private ClaudonyWorkerExecutionManager execManager;
    private CasehubStartupService service;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry();
        caseInstanceRepo = mock(CrossTenantCaseInstanceRepository.class);
        execManager = mock(ClaudonyWorkerExecutionManager.class);
        service = new CasehubStartupService(registry, caseInstanceRepo, execManager);
    }

    @Test
    void invalidCaseId_logsWarnAndSkips() {
        registry.register(session("s1", "not-a-uuid", "agent"));

        int started = service.bootstrapWatchers();

        assertThat(started).isEqualTo(0);
        verify(execManager, never()).watch(any(), any(), any(), any());
    }

    @Test
    void nullCaseInstance_logsInfoAndSkips() {
        UUID caseId = UUID.randomUUID();
        registry.register(session("s2", caseId.toString(), "agent"));
        when(caseInstanceRepo.findByUuid(caseId))
                .thenReturn(Uni.createFrom().item((CaseInstance) null));

        int started = service.bootstrapWatchers();

        assertThat(started).isEqualTo(0);
        verify(execManager, never()).watch(any(), any(), any(), any());
    }

    @Test
    void absentRoleName_fallsBackToWorker() {
        UUID caseId = UUID.randomUUID();
        registry.register(sessionNoRole("s3", caseId.toString()));
        CaseInstance inst = new CaseInstance();
        inst.setUuid(caseId);
        when(caseInstanceRepo.findByUuid(caseId))
                .thenReturn(Uni.createFrom().item(inst));

        int started = service.bootstrapWatchers();

        assertThat(started).isEqualTo(1);
        verify(execManager).watch(
                eq("s3"),
                anyString(),
                eq(inst),
                argThat(w -> "worker".equals(w.getName())));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private io.casehub.claudony.server.model.Session session(
            String id, String caseId, String role) {
        return new io.casehub.claudony.server.model.Session(
                id,
                ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX + id,
                "/tmp", "claude",
                io.casehub.claudony.server.model.SessionStatus.IDLE,
                Instant.now(), Instant.now(),
                Optional.empty(),
                Optional.of(caseId),
                Optional.of(role));
    }

    private io.casehub.claudony.server.model.Session sessionNoRole(String id, String caseId) {
        return new io.casehub.claudony.server.model.Session(
                id,
                ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX + id,
                "/tmp", "claude",
                io.casehub.claudony.server.model.SessionStatus.IDLE,
                Instant.now(), Instant.now(),
                Optional.empty(),
                Optional.of(caseId),
                Optional.empty());
    }
}
