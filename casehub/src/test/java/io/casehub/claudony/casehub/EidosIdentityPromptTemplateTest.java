package io.casehub.claudony.casehub;

import io.casehub.eidos.api.AgentConstraint;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.ConstraintSeverity;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EidosIdentityPromptTemplateTest {

    @Test
    void fullPromptWithBriefingGoalsConstraints() {
        var descriptor = AgentDescriptor.builder()
                .agentId("code-reviewer")
                .name("Code Reviewer")
                .slot("worker")
                .tenancyId("default")
                .briefing("You review code for quality and correctness.")
                .goals(List.of(
                        new AgentGoal("quality", "Ensure code quality", GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(), null),
                        new AgentGoal("internal", "Track metrics", GoalPriority.SECONDARY, Visibility.PRIVATE, List.of(), null)))
                .constraints(List.of(
                        new AgentConstraint("no-force-push", "Never force push", Visibility.PUBLIC, ConstraintSeverity.HARD)))
                .build();

        var result = EidosIdentityPromptTemplate.generate(descriptor);

        assertThat(result).isPresent();
        var prompt = result.get();
        assertThat(prompt).contains("# Identity");
        assertThat(prompt).contains("You review code for quality and correctness.");
        assertThat(prompt).contains("## Goals");
        assertThat(prompt).contains("quality — Ensure code quality");
        assertThat(prompt).doesNotContain("internal");
        assertThat(prompt).contains("## Constraints");
        assertThat(prompt).contains("no-force-push — Never force push");
    }

    @Test
    void briefingOnlyNoGoalsNoConstraints() {
        var descriptor = AgentDescriptor.builder()
                .agentId("simple-agent")
                .name("Simple Agent")
                .slot("worker")
                .tenancyId("default")
                .briefing("A simple agent.")
                .build();

        var result = EidosIdentityPromptTemplate.generate(descriptor);

        assertThat(result).isPresent();
        var prompt = result.get();
        assertThat(prompt).contains("A simple agent.");
        assertThat(prompt).doesNotContain("## Goals");
        assertThat(prompt).doesNotContain("## Constraints");
    }

    @Test
    void nullDescriptorReturnsEmpty() {
        assertThat(EidosIdentityPromptTemplate.generate(null)).isEmpty();
    }

    @Test
    void noBriefingReturnsEmpty() {
        var descriptor = AgentDescriptor.builder()
                .agentId("no-briefing")
                .name("No Briefing")
                .slot("worker")
                .tenancyId("default")
                .build();

        assertThat(EidosIdentityPromptTemplate.generate(descriptor)).isEmpty();
    }


}
