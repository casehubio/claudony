package io.casehub.claudony.casehub;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.model.Goal;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Minimal case definition for ResearcherCaseCompletionTest.
 *
 * No bindings — no provision retry timer. Pure goal-evaluation test:
 * signal "workers.researcher.exited=true" → CaseContextChangedEventHandler evaluates goal
 * → GoalReachedEventHandler → CaseStatusChangedHandler sets COMPLETED.
 */
@ApplicationScoped
public class TestCompletionCase extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
        var goal = Goal.builder()
                .name("research-complete")
                .condition(new JQExpressionEvaluator(".workers.researcher.exited == true"))
                .kind(GoalKind.SUCCESS)
                .build();

        return CaseDefinition.builder()
                .namespace("io.casehub.claudony.test")
                .name("researcher-completion")
                .version("1.0.0")
                .goals(goal)
                .completion(GoalExpression.allOf(goal))
                .build();
    }
}
