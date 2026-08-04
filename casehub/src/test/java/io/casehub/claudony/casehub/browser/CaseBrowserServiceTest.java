package io.casehub.claudony.casehub.browser;

import io.casehub.api.model.CaseStatus;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TenantContext;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaseBrowserServiceTest {

    CaseInstanceRepository caseRepo = mock(CaseInstanceRepository.class);
    SessionRegistry sessionRegistry;
    QhorusDashboardService dashboardService = mock(QhorusDashboardService.class);
    TenantContext tenantContext = mock(TenantContext.class);
    CaseBrowserService service;

    @BeforeEach
    void setUp() {
        when(tenantContext.currentTenantId()).thenReturn("default");
        sessionRegistry = new SessionRegistry(tenantContext);
        when(dashboardService.listChannels()).thenReturn(List.of());
        service = new CaseBrowserService(caseRepo, sessionRegistry, dashboardService, tenantContext);
    }

    @Test
    void listCases_empty() {
        when(caseRepo.findAll("default")).thenReturn(List.of());
        var result = service.listCases();
        assertTrue(result.isEmpty());
    }

    @Test
    void listCases_withActiveWorkers() {
        var uuid = UUID.randomUUID();
        var ci = caseInstance(uuid, CaseStatus.RUNNING, "pr-review");
        when(caseRepo.findAll("default")).thenReturn(List.of(ci));
        sessionRegistry.register(session("s1", uuid.toString(), "reviewer"));

        var result = service.listCases();
        assertEquals(1, result.size());
        assertEquals("RUNNING", result.get(0).status());
        assertEquals("pr-review", result.get(0).definitionName());
        assertEquals(1, result.get(0).activeWorkerCount());
    }

    @Test
    void getCaseDetail_notFound() {
        when(caseRepo.findByUuid(any(), eq("default"))).thenReturn(null);
        assertTrue(service.getCaseDetail(UUID.randomUUID()).isEmpty());
    }

    @Test
    void getCaseDetail_withWorkers() {
        var uuid = UUID.randomUUID();
        var ci = caseInstance(uuid, CaseStatus.RUNNING, "investigation");
        when(caseRepo.findByUuid(uuid, "default")).thenReturn(ci);
        sessionRegistry.register(session("s1", uuid.toString(), "analyst"));

        var result = service.getCaseDetail(uuid);
        assertTrue(result.isPresent());
        assertEquals(1, result.get().workers().size());
        assertEquals("analyst", result.get().workers().get(0).roleName());
    }

    private CaseInstance caseInstance(UUID uuid, CaseStatus status, String name) {
        var ci = new CaseInstance();
        ci.setUuid(uuid);
        ci.setState(status);
        var meta = new CaseMetaModel();
        meta.setName(name);
        ci.setCaseMetaModel(meta);
        return ci;
    }

    private Session session(String id, String caseId, String role) {
        return new Session(id, "session-" + id, "/work", "claude", SessionStatus.ACTIVE,
                Instant.now(), Instant.now(), Optional.empty(),
                Optional.of(caseId), Optional.of(role), "default");
    }
}
