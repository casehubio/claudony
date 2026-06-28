package io.casehub.claudony.server;

import io.casehub.platform.api.identity.TenancyConstants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTenantContextTest {

    @Test
    void returnsDefaultTenantId_whenNoPrincipalResolvable() {
        var ctx = new DefaultTenantContext();
        assertThat(ctx.currentTenantId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
    }
}
