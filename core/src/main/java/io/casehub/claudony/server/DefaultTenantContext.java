package io.casehub.claudony.server;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class DefaultTenantContext implements TenantContext {

    private final Instance<CurrentPrincipal> principal;

    @Inject
    public DefaultTenantContext(Instance<CurrentPrincipal> principal) {
        this.principal = principal;
    }

    DefaultTenantContext() {
        this.principal = null;
    }

    @Override
    public String currentTenantId() {
        if (principal != null && principal.isResolvable()
                && Arc.container() != null
                && Arc.container().requestContext().isActive()) {
            return principal.get().tenancyId();
        }
        return TenancyConstants.DEFAULT_TENANT_ID;
    }
}
