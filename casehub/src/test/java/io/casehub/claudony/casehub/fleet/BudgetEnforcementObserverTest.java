package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.BeforeEach;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.*;

class BudgetEnforcementObserverTest {

    private BudgetTracker tracker;
    private AgentPoolDefinitionRegistry defRegistry;
    private AgentPoolManagerRegistry mgrRegistry;
    private BudgetEnforcer enforcer;
    private BudgetEnforcementObserver observer;

    @BeforeEach
    void setUp() {
        tracker = new BudgetTracker();
        defRegistry = new AgentPoolDefinitionRegistry(new InMemoryRegistryService(event -> {}));
        mgrRegistry = new AgentPoolManagerRegistry();
        enforcer = mock(BudgetEnforcer.class);
        observer = new BudgetEnforcementObserver(tracker, defRegistry, mgrRegistry, enforcer);
    }

    @Test
    void overBudgetTriggersEnforcement() {
        var budget = new BudgetConfig(10.0, null, Duration.ofHours(1),
                EnforcementPolicy.SUSPEND, new ReportInterval.Turn(), Duration.ofMinutes(10));
        defRegistry.register(AgentPoolDefinition.builder().agent("pool-a").pool().budget(budget).build());
        var manager = mock(AgentSessionManager.class);
        mgrRegistry.register("pool-a", manager);

        tracker.record("pool-a", new CostReport("s1", 15.0, 5000, 2000, 0, 0, "opus", Instant.now()));

        observer.onCostReport(new CostReportEvent("pool-a",
                new CostReport("s1", 15.0, 5000, 2000, 0, 0, "opus", Instant.now())));

        verify(enforcer).apply(eq("pool-a"), eq(EnforcementPolicy.SUSPEND), any(), eq(manager));
    }

    @Test
    void alreadyExceededSkips() {
        var budget = new BudgetConfig(10.0, null, Duration.ofHours(1),
                EnforcementPolicy.SUSPEND, new ReportInterval.Turn(), Duration.ofMinutes(10));
        defRegistry.register(AgentPoolDefinition.builder().agent("pool-a").pool().budget(budget).build());

        tracker.setBudgetExceeded("pool-a", true);

        observer.onCostReport(new CostReportEvent("pool-a",
                new CostReport("s1", 15.0, 5000, 2000, 0, 0, "opus", Instant.now())));

        verify(enforcer, never()).apply(any(), any(), any(), any());
    }

    @Test
    void nullBudgetConfigSkips() {
        defRegistry.register(AgentPoolDefinition.builder().agent("pool-a").pool().build());

        observer.onCostReport(new CostReportEvent("pool-a",
                new CostReport("s1", 15.0, 5000, 2000, 0, 0, "opus", Instant.now())));

        verify(enforcer, never()).apply(any(), any(), any(), any());
    }

    @Test
    void underBudgetNoEnforcement() {
        var budget = new BudgetConfig(100.0, null, Duration.ofHours(1),
                EnforcementPolicy.SUSPEND, new ReportInterval.Turn(), Duration.ofMinutes(10));
        defRegistry.register(AgentPoolDefinition.builder().agent("pool-a").pool().budget(budget).build());

        tracker.record("pool-a", new CostReport("s1", 5.0, 1000, 500, 0, 0, "opus", Instant.now()));

        observer.onCostReport(new CostReportEvent("pool-a",
                new CostReport("s1", 5.0, 1000, 500, 0, 0, "opus", Instant.now())));

        verify(enforcer, never()).apply(any(), any(), any(), any());
    }
}
