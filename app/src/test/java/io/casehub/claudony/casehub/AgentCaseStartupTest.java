package io.casehub.claudony.casehub;

import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.converter.CaseDefinitionYamlMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the production YAML case definition parses correctly.
 * Uses CaseDefinitionYamlMapper.load(InputStream) directly — avoids YamlCaseHub's CDI
 * ObjectMapper injection, which is not available outside a Quarkus context.
 */
class AgentCaseStartupTest {

    @Test
    void yamlLoads_withExpectedMetadata() throws IOException {
        InputStream stream = AgentCaseStartupTest.class.getClassLoader()
                .getResourceAsStream("casehub/agent.yaml");
        var def = CaseDefinitionYamlMapper.load(stream);

        assertThat(def).isNotNull();
        assertThat(def.getName()).isEqualTo("agent");
        assertThat(def.getNamespace()).isEqualTo("claudony");
        assertThat(def.getCapabilities())
                .anyMatch(c -> "agent".equals(c.name()));
        assertThat(def.getBindings())
                .anyMatch(b ->
                        "start-session-on-init".equals(b.getName())
                        && b.getOn() instanceof ContextChangeTrigger ctx
                        && ctx.getFilter() == null
                        && b.getWhen() instanceof JQExpressionEvaluator jq
                        && ".workers.agent.started != true and .workers.agent.exited != true".equals(jq.expression()));

        // Verify goals
        assertThat(def.getGoals()).isNotEmpty();
        assertThat(def.getGoals())
                .anyMatch(g ->
                        "agent-complete".equals(g.getName())
                        && g.getCondition() instanceof JQExpressionEvaluator jq
                        && ".workers.agent.exited == true".equals(jq.expression())
                        && GoalKind.SUCCESS.value().equals(g.getKind()));

        // Verify completion
        assertThat(def.getCompletion())
                .isNotNull()
                .isInstanceOf(GoalBasedCompletion.class);

        var completion = (GoalBasedCompletion<?>) def.getCompletion();
        assertThat(completion.getGoals()).isNotEmpty();
    }
}
