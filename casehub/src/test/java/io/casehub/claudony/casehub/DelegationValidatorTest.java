package io.casehub.claudony.casehub;

import io.casehub.eidos.api.OrgAwareDispatch;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DelegationValidatorTest {

    @SuppressWarnings("unchecked")
    private Instance<OrgAwareDispatch> resolvableInstance(OrgAwareDispatch dispatch) {
        Instance<OrgAwareDispatch> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(dispatch);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private Instance<OrgAwareDispatch> unresolvableInstance() {
        Instance<OrgAwareDispatch> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    @Test
    void noOrgStructure_alwaysAllows() {
        var validator = new DelegationValidator(unresolvableInstance(), true);
        assertThat(validator.validate("agent-a", "agent-b", "default")).isTrue();
    }

    @Test
    void subordinate_allowed() {
        var dispatch = mock(OrgAwareDispatch.class);
        when(dispatch.canDelegateTo("manager", "worker", "default")).thenReturn(true);

        var validator = new DelegationValidator(resolvableInstance(dispatch), true);
        assertThat(validator.validate("manager", "worker", "default")).isTrue();
    }

    @Test
    void nonSubordinate_strictMode_rejected() {
        var dispatch = mock(OrgAwareDispatch.class);
        when(dispatch.canDelegateTo("peer-a", "peer-b", "default")).thenReturn(false);

        var validator = new DelegationValidator(resolvableInstance(dispatch), true);
        assertThat(validator.validate("peer-a", "peer-b", "default")).isFalse();
    }

    @Test
    void nonSubordinate_permissiveMode_allowedWithWarning() {
        var dispatch = mock(OrgAwareDispatch.class);
        when(dispatch.canDelegateTo("peer-a", "peer-b", "default")).thenReturn(false);

        var validator = new DelegationValidator(resolvableInstance(dispatch), false);
        assertThat(validator.validate("peer-a", "peer-b", "default")).isTrue();
    }

    @Test
    void strictMode_subordinate_allowed() {
        var dispatch = mock(OrgAwareDispatch.class);
        when(dispatch.canDelegateTo("lead", "dev", "tenant-1")).thenReturn(true);

        var validator = new DelegationValidator(resolvableInstance(dispatch), true);
        assertThat(validator.validate("lead", "dev", "tenant-1")).isTrue();
    }
}
