package io.casehub.claudony.server;

import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.platform.api.identity.TenancyConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRegistryTenantFilterTest {

    static final String TENANT_A = TenancyConstants.DEFAULT_TENANT_ID;
    static final String TENANT_B = "00000000-0000-0000-0000-000000000002";

    private MutableTenantContext tenantCtx;
    private SessionRegistry registry;

    @BeforeEach
    void setUp() {
        tenantCtx = new MutableTenantContext();
        registry = new SessionRegistry(tenantCtx);
    }

    private Session session(String id, String caseId, String tenancyId) {
        return new Session(id, "name-" + id, "/tmp", "cmd", SessionStatus.IDLE,
                Instant.now(), Instant.now(), Optional.empty(),
                Optional.ofNullable(caseId), Optional.empty(), tenancyId);
    }

    @Test
    void all_returnsOnlySessionsMatchingCurrentTenant() {
        registry.register(session("s1", null, TENANT_A));
        registry.register(session("s2", null, TENANT_B));

        tenantCtx.setTenantId(TENANT_A);
        assertThat(registry.all()).extracting(Session::id).containsExactly("s1");

        tenantCtx.setTenantId(TENANT_B);
        assertThat(registry.all()).extracting(Session::id).containsExactly("s2");
    }

    @Test
    void find_returnsEmpty_forOtherTenantSession() {
        registry.register(session("s1", null, TENANT_B));

        tenantCtx.setTenantId(TENANT_A);
        assertThat(registry.find("s1")).isEmpty();
    }

    @Test
    void find_returnsSession_forMatchingTenant() {
        registry.register(session("s1", null, TENANT_A));

        tenantCtx.setTenantId(TENANT_A);
        assertThat(registry.find("s1")).isPresent();
    }

    @Test
    void findByCaseId_filtersByTenant() {
        registry.register(session("s1", "case-1", TENANT_A));
        registry.register(session("s2", "case-1", TENANT_B));

        tenantCtx.setTenantId(TENANT_A);
        assertThat(registry.findByCaseId("case-1")).extracting(Session::id).containsExactly("s1");
    }

    @Test
    void allUnscoped_returnsAllSessionsRegardlessOfTenant() {
        registry.register(session("s1", null, TENANT_A));
        registry.register(session("s2", null, TENANT_B));

        tenantCtx.setTenantId(TENANT_A);
        assertThat(registry.allUnscoped()).hasSize(2);
    }

    @Test
    void findUnscoped_returnsSession_regardlessOfTenant() {
        registry.register(session("s1", null, TENANT_B));

        tenantCtx.setTenantId(TENANT_A);
        assertThat(registry.findUnscoped("s1")).isPresent();
    }

    @Test
    void existsByName_findsNamesAcrossAllTenants() {
        registry.register(session("s1", null, TENANT_B));

        tenantCtx.setTenantId(TENANT_A);
        assertThat(registry.existsByName("name-s1")).isTrue();
        assertThat(registry.existsByName("nonexistent")).isFalse();
    }

    @Test
    void remove_worksOnAnySession_regardlessOfTenant() {
        registry.register(session("s1", null, TENANT_B));

        tenantCtx.setTenantId(TENANT_A);
        assertThat(registry.remove("s1")).isNotNull();
    }

    @Test
    void updateStatus_worksOnAnySession_regardlessOfTenant() {
        registry.register(session("s1", null, TENANT_B));

        tenantCtx.setTenantId(TENANT_A);
        registry.updateStatus("s1", SessionStatus.ACTIVE);
        assertThat(registry.findUnscoped("s1").get().status()).isEqualTo(SessionStatus.ACTIVE);
    }
}
