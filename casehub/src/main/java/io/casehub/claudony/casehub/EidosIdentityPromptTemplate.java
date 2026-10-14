package io.casehub.claudony.casehub;

import io.casehub.eidos.api.AgentDescriptor;

import java.util.Optional;

class EidosIdentityPromptTemplate {

    static Optional<String> generate(AgentDescriptor descriptor) {
        if (descriptor == null) return Optional.empty();
        if (descriptor.briefing() == null || descriptor.briefing().isBlank()) return Optional.empty();

        var sb = new StringBuilder();
        sb.append("# Identity\n\n");
        sb.append(descriptor.briefing()).append("\n");

        var publicGoals = descriptor.publicGoals();
        if (!publicGoals.isEmpty()) {
            sb.append("\n## Goals\n\n");
            for (var goal : publicGoals) {
                sb.append("- ").append(goal.name()).append(" — ").append(goal.description()).append("\n");
            }
        }

        var publicConstraints = descriptor.publicConstraints();
        if (!publicConstraints.isEmpty()) {
            sb.append("\n## Constraints\n\n");
            for (var constraint : publicConstraints) {
                sb.append("- ").append(constraint.name()).append(" — ").append(constraint.description()).append("\n");
            }
        }

        return Optional.of(sb.toString());
    }
}
