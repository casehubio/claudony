package io.casehub.claudony;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.evaluator.ExpressionEvaluator;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Minimal CaseHub subclass for CaseEngineRoundTripTest.
 *
 * No static workers — forces the engine's tryProvision() fallback path,
 * which calls ClaudonyWorkerProvisioner when it advertises the "researcher" capability.
 */
@ApplicationScoped
public class TestResearcherCase extends CaseHub { // public: CDI proxy generation requires it (extends concrete class)

    @Override
    public CaseDefinition getDefinition() {
        Capability cap = Capability.builder()
                .name("researcher")
                .inputSchema("{}")  // empty input mapping — provision with no data
                .outputSchema("{}") // empty output mapping
                .build();

        Binding binding = Binding.builder()
                .name("start-session-on-init")
                .capability(cap)
                .on(new ContextChangeTrigger((ExpressionEvaluator) null))
                .when(".workers.researcher.started != true and .workers.researcher.exited != true")
                .build();

        return CaseDefinition.builder()
                .namespace("io.casehub.claudony.test")
                .name("researcher-round-trip")
                .version("1.0.0")
                .capabilities(cap)
                .bindings(binding)
                .build();
    }
}
