package io.casehub.claudony;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.worker.api.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Minimal CaseHub subclass for CaseEngineRoundTripTest.
 *
 * No static workers — forces the engine's tryProvision() fallback path,
 * which calls ClaudonyWorkerProvisioner when it advertises the "agent" capability.
 */
@ApplicationScoped
public class TestAgentCase extends CaseHub { // public: CDI proxy generation requires it (extends concrete class)

    @Override
    public CaseDefinition getDefinition() {
        Capability cap = Capability.builder()
                .name("agent")
                .inputSchema("{}")  // empty input mapping — provision with no data
                .outputSchema("{}") // empty output mapping
                .build();

        Binding binding = Binding.builder()
                .name("start-session-on-init")
                .capability(cap)
                .on(new ContextChangeTrigger((ExpressionEvaluator) null))
                .when(".workers.agent.started != true and .workers.agent.exited != true")
                .build();

        return CaseDefinition.builder()
                .namespace("io.casehub.claudony.test")
                .name("agent-round-trip")
                .version("1.0.0")
                .capabilities(cap)
                .bindings(binding)
                .build();
    }
}
