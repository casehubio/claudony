package dev.claudony.casehub;

import io.casehub.api.model.WorkerSummary;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class MeshSystemPromptTemplateTest {

    private static final UUID CASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String WORKER_ID = "worker-abc";
    private static final String CAPABILITY = "researcher";

    private static final List<CaseChannelLayout.ChannelSpec> NORMATIVE_SPECS = List.of(
            new CaseChannelLayout.ChannelSpec("work", ChannelSemantic.APPEND, null,
                    "Primary coordination — all obligation-carrying message types"),
            new CaseChannelLayout.ChannelSpec("observe", ChannelSemantic.APPEND,
                    Set.of(MessageType.EVENT), "Telemetry — EVENT only, no obligations created"),
            new CaseChannelLayout.ChannelSpec("oversight", ChannelSemantic.APPEND,
                    Set.of(MessageType.QUERY, MessageType.COMMAND),
                    "Human governance — agent QUERY and human COMMAND")
    );

    private static final List<CaseChannelLayout.ChannelSpec> SIMPLE_SPECS = List.of(
            new CaseChannelLayout.ChannelSpec("work", ChannelSemantic.APPEND, null,
                    "Primary coordination — all obligation-carrying message types"),
            new CaseChannelLayout.ChannelSpec("observe", ChannelSemantic.APPEND,
                    Set.of(MessageType.EVENT), "Telemetry — EVENT only, no obligations created")
    );

    private static WorkerSummary summary(String name, String output) {
        return new WorkerSummary("id-" + name, name, Instant.now().minusSeconds(60),
                Instant.now(), output, UUID.randomUUID());
    }

    // ── Happy path: SILENT ────────────────────────────────────────────────

    @Test
    void silent_returnsEmpty() {
        Optional<String> result = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.SILENT);

        assertThat(result).isEmpty();
    }

    // ── Happy path: ACTIVE ────────────────────────────────────────────────

    @Test
    void active_returnsPresent() {
        Optional<String> result = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.ACTIVE);

        assertThat(result).isPresent();
    }

    @Test
    void active_containsCaseId() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.ACTIVE).orElseThrow();

        assertThat(prompt).contains(CASE_ID.toString());
    }

    @Test
    void active_containsCapabilityAsRole() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.ACTIVE).orElseThrow();

        assertThat(prompt).contains("ROLE: " + CAPABILITY);
    }

    @Test
    void active_normativeLayout_containsAllThreeChannels() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.ACTIVE).orElseThrow();

        assertThat(prompt)
                .contains("case-" + CASE_ID + "/work")
                .contains("case-" + CASE_ID + "/observe")
                .contains("case-" + CASE_ID + "/oversight");
    }

    @Test
    void active_simpleLayout_containsOnlyWorkAndObserve() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, SIMPLE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.ACTIVE).orElseThrow();

        assertThat(prompt)
                .contains("case-" + CASE_ID + "/work")
                .contains("case-" + CASE_ID + "/observe")
                .doesNotContain("case-" + CASE_ID + "/oversight");
    }

    @Test
    void active_containsStartupSection() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.ACTIVE).orElseThrow();

        assertThat(prompt).contains("STARTUP:");
    }

    @Test
    void active_startupContainsWorkerId() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.ACTIVE).orElseThrow();

        assertThat(prompt).contains("register(\"" + WORKER_ID + "\"");
    }

    @Test
    void active_containsMessageDisciplineSection() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.ACTIVE).orElseThrow();

        assertThat(prompt).contains("MESSAGE DISCIPLINE:");
    }

    @Test
    void active_noPriorWorkers_showsNoneMessage() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.ACTIVE).orElseThrow();

        assertThat(prompt).contains("none — you are the first worker");
    }

    @Test
    void active_withPriorWorkers_listsThem() {
        var workers = List.of(
                summary("alice", "auth-analysis complete"),
                summary("bob", null));

        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                workers, MeshParticipationStrategy.MeshParticipation.ACTIVE).orElseThrow();

        assertThat(prompt)
                .contains("alice")
                .contains("auth-analysis complete")
                .contains("bob");
    }

    @Test
    void active_priorWorkerWithNullSummary_noNullInOutput() {
        var workers = List.of(summary("alice", null));

        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                workers, MeshParticipationStrategy.MeshParticipation.ACTIVE).orElseThrow();

        assertThat(prompt)
                .contains("alice")
                .doesNotContain("null");
    }

    // ── Happy path: REACTIVE ──────────────────────────────────────────────

    @Test
    void reactive_returnsPresent() {
        Optional<String> result = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.REACTIVE);

        assertThat(result).isPresent();
    }

    @Test
    void reactive_doesNotContainStartupSection() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.REACTIVE).orElseThrow();

        assertThat(prompt)
                .doesNotContain("STARTUP:")
                .doesNotContain("register(\"");
    }

    @Test
    void reactive_containsWorkChannel() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.REACTIVE).orElseThrow();

        assertThat(prompt).contains("case-" + CASE_ID + "/work");
    }

    @Test
    void reactive_containsMessageDiscipline() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.REACTIVE).orElseThrow();

        assertThat(prompt).contains("MESSAGE DISCIPLINE:");
    }

    @Test
    void reactive_normativeLayout_excludesOversightChannel() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.REACTIVE).orElseThrow();

        assertThat(prompt)
                .doesNotContain("case-" + CASE_ID + "/oversight");
    }

    @Test
    void reactive_emptyChannelSpecs_returnsPromptWithoutChannels() {
        Optional<String> result = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, List.of(),
                List.of(), MeshParticipationStrategy.MeshParticipation.REACTIVE);

        assertThat(result).isPresent();
        assertThat(result.get()).doesNotContain("STARTUP:");
    }

    // ── Correctness ───────────────────────────────────────────────────────

    @Test
    void channelNamesFollowCaseIdPurposePattern() {
        String prompt = MeshSystemPromptTemplate.generate(
                WORKER_ID, CAPABILITY, CASE_ID, NORMATIVE_SPECS,
                List.of(), MeshParticipationStrategy.MeshParticipation.ACTIVE).orElseThrow();

        assertThat(prompt)
                .contains("case-" + CASE_ID + "/work")
                .contains("case-" + CASE_ID + "/observe")
                .contains("case-" + CASE_ID + "/oversight");
    }
}
