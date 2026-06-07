package io.casehub.claudony.casehub;

import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.model.GoalBasedCompletion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the production YAML case definition parses correctly.
 * Instantiates ResearcherCase directly — getDefinition() via YamlCaseHub loads the YAML
 * without touching the CaseHubRuntime injection point, so no Quarkus context is needed.
 */
class ResearcherCaseStartupTest {

    @Test
    void yamlLoads_withExpectedMetadata() {
        var def = new ResearcherCase().getDefinition();

        assertThat(def).isNotNull();
        assertThat(def.getName()).isEqualTo("researcher");
        assertThat(def.getNamespace()).isEqualTo("claudony");
        assertThat(def.getCapabilities())
                .anyMatch(c -> "researcher".equals(c.getName()));
        assertThat(def.getBindings())
                .anyMatch(b ->
                        "start-session-on-init".equals(b.getName())
                        && b.getOn() instanceof ContextChangeTrigger ctx
                        && ctx.getFilter() == null
                        && b.getWhen() instanceof JQExpressionEvaluator jq
                        && ".workers.researcher.exited != true".equals(jq.expression()));

        // Verify goals
        assertThat(def.getGoals()).isNotEmpty();
        assertThat(def.getGoals())
                .anyMatch(g ->
                        "research-complete".equals(g.getName())
                        && g.getCondition() instanceof JQExpressionEvaluator jq
                        && ".workers.researcher.exited == true".equals(jq.expression())
                        && GoalKind.SUCCESS == g.getKind());

        // Verify completion
        assertThat(def.getCompletion())
                .isNotNull()
                .isInstanceOf(GoalBasedCompletion.class);

        var completion = (GoalBasedCompletion) def.getCompletion();
        assertThat(completion.getSuccess()).isNotNull();
    }
}
