package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ScalingSchedulerBudgetTest {

    private AgentPoolDefinitionRegistry defRegistry;
    private AgentPoolManagerRegistry mgrRegistry;
    private BudgetTracker budgetTracker;
    private BudgetEnforcer budgetEnforcer;
    private AgentSessionManager manager;
    private ScalingScheduler scheduler;

    @BeforeEach
    void setUp() {
        defRegistry = new AgentPoolDefinitionRegistry();
        mgrRegistry = new AgentPoolManagerRegistry();
        budgetTracker = new BudgetTracker();
        budgetEnforcer = spy(new BudgetEnforcer());
        manager = mock(AgentSessionManager.class);
        when(manager.status()).thenReturn(new AgentPoolStatus(0, 10, 2, 0, 2, AgentPoolHealth.HEALTHY));
        when(manager.config()).thenReturn(new AgentSessionManagerConfig(0, 10));
        when(manager.snapshotAndResetDemandMetrics(any())).thenReturn(PoolSnapshot.DemandMetrics.ZERO);
        when(manager.sessions()).thenReturn(List.of());
        mgrRegistry.register("pool-a", manager);
    }

    @Test
    void budgetExceededSkipsScaling() {
        var budget = new BudgetConfig(10.0, null, Duration.ofHours(1),
                EnforcementPolicy.BLOCK_NEW, new ReportInterval.Turn(), Duration.ofMinutes(10));
        defRegistry.register(AgentPoolDefinition.builder().agent("pool-a").pool()
                .scaling(new ScalingConfig.TargetTrackingConfig(0.7, Duration.ofSeconds(60), null))
                .budget(budget).build());

        budgetTracker.record("pool-a", new CostReport("s1", 15.0, 5000, 2000, 0, 0, "opus", Instant.now()));

        scheduler = new ScalingScheduler(defRegistry, mgrRegistry, null, List.of(), budgetTracker, budgetEnforcer);
        scheduler.tick();

        assertThat(budgetTracker.isBudgetExceeded("pool-a")).isTrue();
        verify(budgetEnforcer).apply(eq("pool-a"), eq(EnforcementPolicy.BLOCK_NEW), any(), eq(manager));
        verify(manager, times(1)).adjustMaxActive(0);
    }

    @Test
    void noBudgetConfigSkipsBudgetCheck() {
        defRegistry.register(AgentPoolDefinition.builder().agent("pool-a").pool()
                .scaling(new ScalingConfig.TargetTrackingConfig(0.7, Duration.ofSeconds(60), null))
                .build());

        scheduler = new ScalingScheduler(defRegistry, mgrRegistry, null, List.of(), budgetTracker, budgetEnforcer);
        scheduler.tick();

        assertThat(budgetTracker.isBudgetExceeded("pool-a")).isFalse();
        verify(budgetEnforcer, never()).apply(any(), any(), any(), any());
    }

    @Test
    void budgetRecoveryRestoresMaxActive() {
        var budget = new BudgetConfig(100.0, null, Duration.ofHours(1),
                EnforcementPolicy.BLOCK_NEW, new ReportInterval.Turn(), Duration.ofMinutes(10));
        defRegistry.register(AgentPoolDefinition.builder().agent("pool-a").pool()
                .maxActive(10).budget(budget).build());

        budgetTracker.setBudgetExceeded("pool-a", true);

        scheduler = new ScalingScheduler(defRegistry, mgrRegistry, null, List.of(), budgetTracker, budgetEnforcer);
        scheduler.tick();

        assertThat(budgetTracker.isBudgetExceeded("pool-a")).isFalse();
        verify(manager).adjustMaxActive(10);
    }

    @Test
    void budgetEvaluatedBeforeProactive() {
        var budget = new BudgetConfig(10.0, null, Duration.ofHours(1),
                EnforcementPolicy.SUSPEND, new ReportInterval.Turn(), Duration.ofMinutes(10));
        defRegistry.register(AgentPoolDefinition.builder().agent("pool-a").pool()
                .scaling(new ScalingConfig.ProactiveConfig(3, Duration.ofSeconds(30), null))
                .budget(budget).build());

        budgetTracker.record("pool-a", new CostReport("s1", 15.0, 5000, 2000, 0, 0, "opus", Instant.now()));

        scheduler = new ScalingScheduler(defRegistry, mgrRegistry, null, List.of(), budgetTracker, budgetEnforcer);
        scheduler.tick();

        assertThat(budgetTracker.isBudgetExceeded("pool-a")).isTrue();
        verify(manager, never()).resumeSession(any());
    }

    @Test
    void flushCalledOnTick() {
        var budget = new BudgetConfig(100.0, null, Duration.ofHours(1),
                EnforcementPolicy.ALERT, new ReportInterval.Turn(), Duration.ofMinutes(10));
        defRegistry.register(AgentPoolDefinition.builder().agent("pool-a").pool().budget(budget).build());

        budgetTracker.record("pool-a", new CostReport("s1", 5.0, 1000, 500, 0, 0, "opus", Instant.now()));

        var trackerSpy = spy(budgetTracker);
        scheduler = new ScalingScheduler(defRegistry, mgrRegistry, null, List.of(), trackerSpy, budgetEnforcer);
        scheduler.tick();

        verify(trackerSpy).flush("pool-a");
    }
}
