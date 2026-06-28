package io.casehub.claudony.server;

import io.casehub.platform.api.identity.TenancyConstants;

public class MutableTenantContext implements TenantContext {

    private String tenantId = TenancyConstants.DEFAULT_TENANT_ID;

    public void setTenantId(String id) { this.tenantId = id; }

    public void resetForTest() { this.tenantId = TenancyConstants.DEFAULT_TENANT_ID; }

    @Override
    public String currentTenantId() { return tenantId; }
}
