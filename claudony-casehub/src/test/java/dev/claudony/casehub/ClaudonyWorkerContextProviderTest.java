package dev.claudony.casehub;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerSummary;
import io.casehub.api.spi.CaseChannelProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudonyWorkerContextProviderTest {

    private CaseLineageQuery lineageQuery;
    private CaseChannelProvider channelProvider;
    private ClaudonyWorkerContextProvider provider;

    @BeforeEach
    void setUp() {
        lineageQuery = mock(CaseLineageQuery.class);
        channelProvider = mock(CaseChannelProvider.class);
        provider = new ClaudonyWorkerContextProvider(lineageQuery, channelProvider);
    }

    @Test
    void buildContext_noPriorWorkers_returnsEmptyPriorWorkers() {
        UUID caseId = UUID.randomUUID();
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(List.of());
        when(channelProvider.listChannels(caseId)).thenReturn(List.of());

        WorkerContext ctx = provider.buildContext("worker-1",
                WorkRequest.of("researcher", Map.of("caseId", caseId.toString())));

        assertThat(ctx.priorWorkers()).isEmpty();
        assertThat(ctx.taskDescription()).isEqualTo("researcher");
    }

    @Test
    void buildContext_withCompletedPriorWorkers_includesThem() {
        UUID caseId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        var summary = new WorkerSummary("alice", "AliceRole", null, Instant.now().minusSeconds(60), null, entryId);
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(List.of(summary));
        when(channelProvider.listChannels(caseId)).thenReturn(List.of());

        WorkerContext ctx = provider.buildContext("worker-2",
                WorkRequest.of("coder", Map.of("caseId", caseId.toString())));

        assertThat(ctx.priorWorkers()).hasSize(1);
        assertThat(ctx.priorWorkers().get(0).workerId()).isEqualTo("alice");
        assertThat(ctx.priorWorkers().get(0).workerName()).isEqualTo("AliceRole");
        assertThat(ctx.priorWorkers().get(0).ledgerEntryId()).isEqualTo(entryId);
    }

    @Test
    void buildContext_withChannel_includesChannelInContext() {
        UUID caseId = UUID.randomUUID();
        var channel = new CaseChannel("ch-id", "case-coord", "coordination", "qhorus", Map.of());
        when(lineageQuery.findCompletedWorkers(caseId)).thenReturn(List.of());
        when(channelProvider.listChannels(caseId)).thenReturn(List.of(channel));

        WorkerContext ctx = provider.buildContext("worker-1",
                WorkRequest.of("task", Map.of("caseId", caseId.toString())));

        assertThat(ctx.channel()).isNotNull();
        assertThat(ctx.channel().name()).isEqualTo("case-coord");
    }

    @Test
    void buildContext_cleanStart_returnsEmptyPriorWorkers() {
        WorkerContext ctx = provider.buildContext("worker-new",
                WorkRequest.of("task", Map.of("clean-start", true)));

        assertThat(ctx.priorWorkers()).isEmpty();
        verifyNoInteractions(lineageQuery);
        verifyNoInteractions(channelProvider);
    }

    @Test
    void buildContext_missingCaseId_returnsEmptyContext() {
        WorkerContext ctx = provider.buildContext("worker-1",
                WorkRequest.of("task", Map.of()));

        assertThat(ctx.priorWorkers()).isEmpty();
        assertThat(ctx.caseId()).isNull();
        verifyNoInteractions(lineageQuery);
    }

    @Test
    void buildContext_propagationContextIsAlwaysSet() {
        WorkerContext ctx = provider.buildContext("worker-1",
                WorkRequest.of("task", Map.of()));

        assertThat(ctx.propagationContext()).isNotNull();
    }
}
