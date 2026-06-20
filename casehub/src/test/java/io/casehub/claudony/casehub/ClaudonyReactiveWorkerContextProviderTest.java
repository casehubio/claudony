package io.casehub.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerSummary;
import io.casehub.api.spi.ReactiveCaseChannelProvider;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudonyReactiveWorkerContextProviderTest {

    private CaseLineageQuery                    lineageQuery;
    private ReactiveCaseChannelProvider         channelProvider;
    private ClaudonyReactiveWorkerContextProvider provider;

    @BeforeEach
    void setUp() {
        lineageQuery    = mock(CaseLineageQuery.class);
        channelProvider = mock(ReactiveCaseChannelProvider.class);
        provider = new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider);
    }

    // ── Core context assembly ─────────────────────────────────────────────

    @Test
    void buildContext_noPriorWorkers_returnsEmptyPriorWorkers() {
        UUID caseId = UUID.randomUUID();
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(Uni.createFrom().item(List.of()));
        when(channelProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of()));

        WorkerContext ctx = provider.buildContext("worker-1", caseId,
                WorkRequest.of("agent", Map.of()))
                .await().indefinitely();

        assertThat(ctx.priorWorkers()).isEmpty();
        assertThat(ctx.taskDescription()).isEqualTo("agent");
    }

    @Test
    void buildContext_withCompletedPriorWorkers_includesThem() {
        UUID caseId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        var summary = new WorkerSummary("alice", "AliceRole", null, Instant.now().minusSeconds(60), null, entryId);
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(Uni.createFrom().item(List.of(summary)));
        when(channelProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of()));

        WorkerContext ctx = provider.buildContext("worker-2", caseId,
                WorkRequest.of("coder", Map.of()))
                .await().indefinitely();

        assertThat(ctx.priorWorkers()).hasSize(1);
        assertThat(ctx.priorWorkers().get(0).workerId()).isEqualTo("alice");
        assertThat(ctx.priorWorkers().get(0).workerName()).isEqualTo("AliceRole");
        assertThat(ctx.priorWorkers().get(0).ledgerEntryId()).isEqualTo(entryId);
    }

    @Test
    void buildContext_withChannel_includesChannelInContext() {
        UUID caseId = UUID.randomUUID();
        var channel = new CaseChannel("ch-id", "case-coord", "coordination", "qhorus", Map.of());
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(Uni.createFrom().item(List.of()));
        when(channelProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of(channel)));

        WorkerContext ctx = provider.buildContext("worker-1", caseId,
                WorkRequest.of("task", Map.of()))
                .await().indefinitely();

        assertThat(ctx.channels()).isNotEmpty();
        assertThat(ctx.channels().getFirst().name()).isEqualTo("case-coord");
    }

    // ── Early-exit paths: clean-start and null caseId ─────────────────────

    @Test
    void buildContext_cleanStart_returnsEmptyContextWithNoInteractions() {
        WorkerContext ctx = provider.buildContext("worker-new", null,
                WorkRequest.of("task", Map.of("clean-start", true)))
                .await().indefinitely();

        assertThat(ctx.priorWorkers()).isEmpty();
        assertThat(ctx.properties()).containsKey("clean-start");
        verifyNoInteractions(lineageQuery);
        verifyNoInteractions(channelProvider);
    }

    @Test
    void buildContext_missingCaseId_returnsEmptyContextWithNoInteractions() {
        WorkerContext ctx = provider.buildContext("worker-1", null,
                WorkRequest.of("task", Map.of()))
                .await().indefinitely();

        assertThat(ctx.priorWorkers()).isEmpty();
        assertThat(ctx.caseId()).isNull();
        verifyNoInteractions(lineageQuery);
        verifyNoInteractions(channelProvider);
    }

    // ── meshParticipation stamp on all 3 strategies ───────────────────────

    @Test
    void buildContext_activeStrategy_stampsMeshParticipationActive() {
        var p = new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider,
                new ActiveParticipationStrategy());
        UUID caseId = UUID.randomUUID();
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(Uni.createFrom().item(List.of()));
        when(channelProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of()));

        WorkerContext ctx = p.buildContext("w1", caseId,
                WorkRequest.of("task", Map.of()))
                .await().indefinitely();

        assertThat(ctx.properties()).containsEntry("meshParticipation", "ACTIVE");
    }

    @Test
    void buildContext_reactiveStrategy_stampsMeshParticipationReactive() {
        var p = new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider,
                new ReactiveParticipationStrategy());
        UUID caseId = UUID.randomUUID();
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(Uni.createFrom().item(List.of()));
        when(channelProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of()));

        WorkerContext ctx = p.buildContext("w1", caseId,
                WorkRequest.of("task", Map.of()))
                .await().indefinitely();

        assertThat(ctx.properties()).containsEntry("meshParticipation", "REACTIVE");
    }

    @Test
    void buildContext_silentStrategy_stampsMeshParticipationSilent() {
        var p = new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider,
                new SilentParticipationStrategy());
        UUID caseId = UUID.randomUUID();
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(Uni.createFrom().item(List.of()));
        when(channelProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of()));

        WorkerContext ctx = p.buildContext("w1", caseId,
                WorkRequest.of("task", Map.of()))
                .await().indefinitely();

        assertThat(ctx.properties()).containsEntry("meshParticipation", "SILENT");
    }

    // ── meshParticipation stamped on early-exit paths ─────────────────────

    @Test
    void buildContext_cleanStart_meshParticipationStamped() {
        var silentProvider = new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider,
                new SilentParticipationStrategy());

        WorkerContext ctx = silentProvider.buildContext("w1", null,
                WorkRequest.of("task", Map.of("clean-start", true)))
                .await().indefinitely();

        assertThat(ctx.properties()).containsEntry("meshParticipation", "SILENT");
        verifyNoInteractions(lineageQuery);
        verifyNoInteractions(channelProvider);
    }

    @Test
    void buildContext_missingCaseId_meshParticipationStamped() {
        var silentProvider = new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider,
                new SilentParticipationStrategy());

        WorkerContext ctx = silentProvider.buildContext("w1", null,
                WorkRequest.of("task", Map.of()))
                .await().indefinitely();

        assertThat(ctx.properties()).containsEntry("meshParticipation", "SILENT");
    }

    // ── systemPrompt presence and absence ────────────────────────────────

    @Test
    void buildContext_activeStrategy_withCaseId_systemPromptPresent() {
        var p = new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider,
                new ActiveParticipationStrategy(), new NormativeChannelLayout());
        UUID caseId = UUID.randomUUID();
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(Uni.createFrom().item(List.of()));
        when(channelProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of()));

        WorkerContext ctx = p.buildContext("w1", caseId,
                WorkRequest.of("agent", Map.of()))
                .await().indefinitely();

        assertThat(ctx.properties()).containsKey("systemPrompt");
    }

    @Test
    void buildContext_silentStrategy_withCaseId_systemPromptAbsent() {
        var p = new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider,
                new SilentParticipationStrategy(), new NormativeChannelLayout());
        UUID caseId = UUID.randomUUID();
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(Uni.createFrom().item(List.of()));
        when(channelProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of()));

        WorkerContext ctx = p.buildContext("w1", caseId,
                WorkRequest.of("agent", Map.of()))
                .await().indefinitely();

        assertThat(ctx.properties()).doesNotContainKey("systemPrompt");
    }

    // ── systemPrompt content correctness ─────────────────────────────────

    @Test
    void buildContext_activeStrategy_systemPromptContainsCaseId() {
        var p = new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider,
                new ActiveParticipationStrategy(), new NormativeChannelLayout());
        UUID caseId = UUID.randomUUID();
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(Uni.createFrom().item(List.of()));
        when(channelProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of()));

        WorkerContext ctx = p.buildContext("w1", caseId,
                WorkRequest.of("agent", Map.of()))
                .await().indefinitely();

        String prompt = (String) ctx.properties().get("systemPrompt");
        assertThat(prompt).contains(caseId.toString());
    }

    @Test
    void buildContext_activeStrategy_systemPromptContainsCapability() {
        var p = new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider,
                new ActiveParticipationStrategy(), new NormativeChannelLayout());
        UUID caseId = UUID.randomUUID();
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(Uni.createFrom().item(List.of()));
        when(channelProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of()));

        WorkerContext ctx = p.buildContext("w1", caseId,
                WorkRequest.of("code-reviewer", Map.of()))
                .await().indefinitely();

        String prompt = (String) ctx.properties().get("systemPrompt");
        assertThat(prompt).contains("code-reviewer");
    }

    @Test
    void buildContext_reactiveStrategy_systemPromptLacksStartupSection() {
        var p = new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider,
                new ReactiveParticipationStrategy(), new NormativeChannelLayout());
        UUID caseId = UUID.randomUUID();
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(Uni.createFrom().item(List.of()));
        when(channelProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of()));

        WorkerContext ctx = p.buildContext("w1", caseId,
                WorkRequest.of("analyst", Map.of()))
                .await().indefinitely();

        String prompt = (String) ctx.properties().get("systemPrompt");
        assertThat(prompt)
                .doesNotContain("STARTUP:")
                .doesNotContain("register(\"");
    }

    // ── Propagation context ───────────────────────────────────────────────

    @Test
    void buildContext_propagationContextIsAlwaysSet() {
        WorkerContext ctx = provider.buildContext("worker-1", null,
                WorkRequest.of("task", Map.of()))
                .await().indefinitely();

        assertThat(ctx.propagationContext()).isNotNull();
    }

    // ── Bad config throws at construction time ────────────────────────────

    @Test
    void cdiConstructor_badMeshParticipationConfig_throwsIllegalArgumentException() {
        CaseHubConfig config = mock(CaseHubConfig.class);
        when(config.meshParticipation()).thenReturn("bogus");
        when(config.channelLayout()).thenReturn("normative");

        assertThatThrownBy(() -> new ClaudonyReactiveWorkerContextProvider(lineageQuery, channelProvider, config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
    }

    // ── Parallel execution: both queries are called ───────────────────────

    @Test
    void buildContext_lineageAndChannelRunInParallel() {
        UUID caseId = UUID.randomUUID();
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(Uni.createFrom().item(List.of()));
        when(channelProvider.listChannels(caseId)).thenReturn(Uni.createFrom().item(List.of()));

        provider.buildContext("w1", caseId, WorkRequest.of("task", Map.of()))
                .await().indefinitely();

        // Both must have been called — they run in the parallel combine
        verify(lineageQuery).findCompletedWorkers(caseId);
        verify(channelProvider).listChannels(caseId);
    }
}
