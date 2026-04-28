package dev.claudony;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * CaseHub subclass used by CaseEngineRoundTripE2ETest.
 *
 * <p>Defines a case with a single "researcher" capability bound to a ContextChangeTrigger that
 * fires when the "topic" key is set. No static workers — the engine delegates to
 * ClaudonyWorkerProvisioner when no pre-defined worker matches the capability.
 *
 * <p>Kept as a separate top-level bean (not an inner class) so that CDI scanning picks it up
 * correctly within the @TestProfile context.
 */
@ApplicationScoped
public class TestResearcherCase extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
        Capability cap = Capability.builder()
                .name("researcher")
                .inputSchema(".")
                .outputSchema(".")
                .build();

        Binding binding = Binding.builder()
                .name("start-researcher-on-topic")
                .capability(cap)
                .on(new ContextChangeTrigger(".topic != null"))
                .build();

        return CaseDefinition.builder()
                .namespace("dev.claudony.test")
                .name("researcher-round-trip")
                .version("1.0.0")
                .capabilities(cap)
                .bindings(binding)
                .build();
    }
}
