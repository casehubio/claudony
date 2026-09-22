package io.casehub.claudony;

import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.PlanAdapter;
import io.casehub.neocortex.memory.cbr.PlanEnsembleAnalyzer;
import io.casehub.neocortex.memory.cbr.runtime.NoOpCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.runtime.NoOpPlanAdapter;
import io.casehub.neocortex.memory.cbr.runtime.NoOpPlanEnsembleAnalyzer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class NoOpNeocortexBeans {

    @Produces
    @DefaultBean
    @ApplicationScoped
    CbrCaseMemoryStore cbrCaseMemoryStore() {
        return new NoOpCbrCaseMemoryStore();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    PlanAdapter planAdapter() {
        return new NoOpPlanAdapter();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    PlanEnsembleAnalyzer planEnsembleAnalyzer() {
        return new NoOpPlanEnsembleAnalyzer();
    }
}
