package io.casehub.claudony.casehub.fleet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

@ApplicationScoped
public class BudgetEnforcementObserver {

    private final BudgetTracker tracker;
    private final AgentPoolDefinitionRegistry defRegistry;
    private final AgentPoolManagerRegistry mgrRegistry;
    private final BudgetEnforcer enforcer;

    @Inject
    public BudgetEnforcementObserver(BudgetTracker tracker,
                                     AgentPoolDefinitionRegistry defRegistry,
                                     AgentPoolManagerRegistry mgrRegistry,
                                     BudgetEnforcer enforcer) {
        this.tracker = tracker;
        this.defRegistry = defRegistry;
        this.mgrRegistry = mgrRegistry;
        this.enforcer = enforcer;
    }

    void onCostReport(@ObservesAsync CostReportEvent event) {
        if (tracker.isBudgetExceeded(event.poolName())) return;

        var def = defRegistry.get(event.poolName()).orElse(null);
        if (def == null) return;

        var config = def.pool().budget();
        if (config == null) return;

        var result = tracker.checkBudget(event.poolName(), config);
        if (result.overBudget()) {
            tracker.setBudgetExceeded(event.poolName(), true);
            var manager = mgrRegistry.get(event.poolName()).orElse(null);
            if (manager != null) {
                enforcer.apply(event.poolName(), config.enforcement(), result, manager);
            }
        }
    }
}
