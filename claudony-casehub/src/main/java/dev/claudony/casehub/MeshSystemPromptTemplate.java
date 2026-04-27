package dev.claudony.casehub;

import io.casehub.api.model.WorkerSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class MeshSystemPromptTemplate {

    static Optional<String> generate(
            String workerId,
            String capability,
            UUID caseId,
            List<CaseChannelLayout.ChannelSpec> channelSpecs,
            List<WorkerSummary> priorWorkers,
            MeshParticipationStrategy.MeshParticipation participation) {
        return switch (participation) {
            case ACTIVE -> Optional.of(buildActive(workerId, capability, caseId, channelSpecs, priorWorkers));
            case REACTIVE -> Optional.of(buildReactive(capability, caseId, channelSpecs, priorWorkers));
            case SILENT -> Optional.empty();
        };
    }

    private static String buildActive(String workerId, String capability, UUID caseId,
                                       List<CaseChannelLayout.ChannelSpec> channelSpecs,
                                       List<WorkerSummary> priorWorkers) {
        return "You are a Claudony-managed agent working on case " + caseId + ".\n\n"
                + "ROLE: " + capability + "\n\n"
                + "MESH CHANNELS:\n" + formatChannels(caseId, channelSpecs) + "\n"
                + "STARTUP:\n"
                + "  1. register(\"" + workerId + "\", \"Starting " + capability + "\", [\"" + capability + "\"])\n"
                + "  2. send_message(\"case-" + caseId + "/work\", STATUS, \"Starting: " + capability + "\")\n\n"
                + "PRIOR WORKERS:\n" + formatPriorWorkers(priorWorkers) + "\n"
                + "MESSAGE DISCIPLINE:\n"
                + "  - Post EVENT to observe for every significant tool call (no obligations created)\n"
                + "  - Post STATUS to work when you reach major milestones\n"
                + "  - Use QUERY/RESPONSE for questions with other agents — these create obligations\n"
                + "  - Use HANDOFF to pass work to a named next worker\n"
                + "  - Use DONE only when your task is fully complete\n"
                + "  - If you cannot proceed: DECLINE with a clear reason\n"
                + "  - Check work channel every few steps: check_messages(\"case-" + caseId + "/work\", afterId=N)\n"
                + "  - Check oversight if expecting human input\n";
    }

    private static String buildReactive(String capability, UUID caseId,
                                         List<CaseChannelLayout.ChannelSpec> channelSpecs,
                                         List<WorkerSummary> priorWorkers) {
        List<CaseChannelLayout.ChannelSpec> workOnly = channelSpecs.stream()
                .filter(s -> s.purpose().equals("work"))
                .toList();
        List<CaseChannelLayout.ChannelSpec> channelsToShow = workOnly.isEmpty() ? channelSpecs : workOnly;

        return "You are a Claudony-managed agent working on case " + caseId + ".\n\n"
                + "ROLE: " + capability + "\n\n"
                + "MESH CHANNELS (respond when directly addressed):\n"
                + formatChannels(caseId, channelsToShow) + "\n"
                + "PRIOR WORKERS:\n" + formatPriorWorkers(priorWorkers) + "\n"
                + "MESSAGE DISCIPLINE:\n"
                + "  - Monitor work channel for QUERY or COMMAND addressed to you\n"
                + "  - Use RESPONSE to answer QUERY; DONE when work is complete\n"
                + "  - Post EVENT to observe for diagnostic output\n";
    }

    private static String formatChannels(UUID caseId, List<CaseChannelLayout.ChannelSpec> specs) {
        var sb = new StringBuilder();
        for (var spec : specs) {
            sb.append("  ").append(spec.purpose())
              .append(": case-").append(caseId).append("/").append(spec.purpose())
              .append(" — ").append(spec.description())
              .append("\n");
        }
        return sb.toString();
    }

    private static String formatPriorWorkers(List<WorkerSummary> priorWorkers) {
        if (priorWorkers.isEmpty()) {
            return "  (none — you are the first worker on this case)\n";
        }
        var sb = new StringBuilder();
        for (var w : priorWorkers) {
            sb.append("  - ").append(w.workerName());
            if (w.outputSummary() != null && !w.outputSummary().isBlank()) {
                sb.append(": ").append(w.outputSummary());
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
