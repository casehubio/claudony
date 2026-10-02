package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.*;

class BudgetEnforcerTest {

    private final BudgetEnforcer enforcer = new BudgetEnforcer();

    @Test
    void suspendSuspendsBeforeAdjusting() {
        var manager = mock(AgentSessionManager.class);
        var session = new ManagedSession("s1", "reviewer", "/tmp", null);
        when(manager.sessions()).thenReturn(List.of(session));
        when(manager.adjustMaxActive(0)).thenReturn(0);
        var result = budgetExceeded(BudgetDimension.COST);

        enforcer.apply("pool-a", EnforcementPolicy.SUSPEND, result, manager);

        var order = inOrder(manager);
        order.verify(manager).suspendSession("s1");
        order.verify(manager).adjustMaxActive(0);
    }

    @Test
    void suspendHandlesMultipleSessions() {
        var manager = mock(AgentSessionManager.class);
        var s1 = new ManagedSession("s1", "reviewer", "/tmp", null);
        var s2 = new ManagedSession("s2", "coder", "/tmp", null);
        var suspended = new ManagedSession("s3", "checker", "/tmp", null);
        suspended.setState(SessionState.SUSPENDED);
        when(manager.sessions()).thenReturn(List.of(s1, s2, suspended));
        when(manager.adjustMaxActive(0)).thenReturn(0);

        enforcer.apply("pool-a", EnforcementPolicy.SUSPEND, budgetExceeded(BudgetDimension.COST), manager);

        verify(manager).suspendSession("s1");
        verify(manager).suspendSession("s2");
        verify(manager, never()).suspendSession("s3");
    }

    @Test
    void blockNewOnlyAdjustsMax() {
        var manager = mock(AgentSessionManager.class);
        when(manager.adjustMaxActive(0)).thenReturn(0);

        enforcer.apply("pool-a", EnforcementPolicy.BLOCK_NEW, budgetExceeded(BudgetDimension.COST), manager);

        verify(manager).adjustMaxActive(0);
        verify(manager, never()).suspendSession(any());
    }

    @Test
    void alertScalesToMinActive() {
        var manager = mock(AgentSessionManager.class);
        when(manager.config()).thenReturn(new AgentSessionManagerConfig(2, 10));
        when(manager.adjustMaxActive(2)).thenReturn(2);

        enforcer.apply("pool-a", EnforcementPolicy.ALERT, budgetExceeded(BudgetDimension.TOKENS), manager);

        verify(manager).adjustMaxActive(2);
        verify(manager, never()).suspendSession(any());
    }

    private static BudgetCheckResult budgetExceeded(BudgetDimension dimension) {
        return new BudgetCheckResult(true, dimension, 51.0, 50.0, 15000000, 10000000, Duration.ZERO);
    }
}
