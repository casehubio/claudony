package io.casehub.claudony.casehub;

import io.casehub.eidos.api.OrgAwareDispatch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DelegationValidator {

    private static final Logger LOG = Logger.getLogger(DelegationValidator.class);

    private final Instance<OrgAwareDispatch> orgDispatch;
    private final boolean strict;

    @Inject
    public DelegationValidator(Instance<OrgAwareDispatch> orgDispatch, CaseHubConfig config) {
        this.orgDispatch = orgDispatch;
        this.strict = "strict".equalsIgnoreCase(config.delegationStrictness());
    }

    DelegationValidator(Instance<OrgAwareDispatch> orgDispatch, boolean strict) {
        this.orgDispatch = orgDispatch;
        this.strict = strict;
    }

    public boolean validate(String fromAgentId, String toAgentId, String tenancyId) {
        if (!orgDispatch.isResolvable()) {
            return true;
        }

        boolean allowed = orgDispatch.get().canDelegateTo(fromAgentId, toAgentId, tenancyId);
        if (allowed) {
            return true;
        }

        if (strict) {
            LOG.warnf("Delegation rejected (strict mode): %s cannot delegate to %s", fromAgentId, toAgentId);
            return false;
        }

        LOG.warnf("Delegation outside org scope (permissive mode): %s delegating to %s", fromAgentId, toAgentId);
        return true;
    }
}
