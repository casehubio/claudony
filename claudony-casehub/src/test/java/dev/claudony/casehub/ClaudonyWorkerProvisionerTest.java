package dev.claudony.casehub;

import dev.claudony.server.SessionRegistry;
import dev.claudony.server.TmuxService;
import dev.claudony.server.model.Session;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.spi.ProvisioningException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudonyWorkerProvisionerTest {

    private TmuxService tmux;
    private SessionRegistry registry;
    private ClaudonyWorkerContextProvider contextProvider;
    private WorkerCommandResolver resolver;
    private WorkerSessionMapping sessionMapping;
    private ClaudonyWorkerProvisioner provisioner;

    @BeforeEach
    void setUp() {
        tmux = mock(TmuxService.class);
        registry = mock(SessionRegistry.class);
        contextProvider = mock(ClaudonyWorkerContextProvider.class);
        sessionMapping = new WorkerSessionMapping();
        resolver = new WorkerCommandResolver(Map.of("code-reviewer", "claude", "default", "claude"));
        provisioner = new ClaudonyWorkerProvisioner(true, tmux, registry, resolver, contextProvider, sessionMapping, "/tmp/workers");
    }

    @Test
    void provision_createsSessionAndRegistersWorker() throws Exception {
        var caseId = UUID.randomUUID();
        when(contextProvider.buildContext(anyString(), any())).thenReturn(
                new WorkerContext("refactor auth", caseId, null, List.of(),
                        PropagationContext.createRoot(), Map.of()));

        Worker worker = provisioner.provision(Set.of("code-reviewer"), provisionContext(caseId));

        // Worker name is now the role/task-type, not a UUID
        assertThat(worker.getName()).isEqualTo("code-reviewer");
        verify(tmux).createSession(contains(ClaudonyWorkerProvisioner.SESSION_PREFIX), eq("/tmp/workers"), eq("claude"));
        verify(registry).register(any(Session.class));
    }

    @Test
    void provision_returnsWorkerWithRequestedCapabilities() throws Exception {
        var caseId = UUID.randomUUID();
        when(contextProvider.buildContext(anyString(), any())).thenReturn(
                new WorkerContext("task", caseId, null, List.of(), PropagationContext.createRoot(), Map.of()));

        Worker worker = provisioner.provision(Set.of("code-reviewer"), provisionContext(caseId));

        assertThat(worker.getCapabilities()).extracting(c -> c.getName())
                .containsExactlyInAnyOrder("code-reviewer");
    }

    @Test
    void provision_registersRoleToSessionMapping() throws Exception {
        var caseId = UUID.randomUUID();
        when(contextProvider.buildContext(anyString(), any())).thenReturn(
                new WorkerContext("task", caseId, null, List.of(), PropagationContext.createRoot(), Map.of()));

        provisioner.provision(Set.of("code-reviewer"), provisionContext(caseId));

        // The mapping should resolve "code-reviewer" to a tmux session UUID
        assertThat(sessionMapping.findByRole("code-reviewer")).isPresent();
        assertThat(sessionMapping.findByCase(caseId.toString(), "code-reviewer")).isPresent();
    }

    @Test
    void provision_disabled_throwsProvisioningException() {
        var disabledProvisioner = new ClaudonyWorkerProvisioner(
                false, tmux, registry, resolver, contextProvider, sessionMapping, "/tmp");

        assertThatThrownBy(() -> disabledProvisioner.provision(Set.of("code-reviewer"), provisionContext(UUID.randomUUID())))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void provision_tmuxFails_throwsProvisioningException() throws Exception {
        when(contextProvider.buildContext(anyString(), any())).thenReturn(
                new WorkerContext("task", null, null, List.of(), PropagationContext.createRoot(), Map.of()));
        doThrow(new java.io.IOException("tmux not found")).when(tmux)
                .createSession(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> provisioner.provision(Set.of("code-reviewer"), provisionContext(UUID.randomUUID())))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Failed to create tmux session");
    }

    @Test
    void terminate_killsSessionAndRemovesFromRegistry() throws Exception {
        provisioner.terminate("worker-abc");

        verify(tmux).killSession(ClaudonyWorkerProvisioner.SESSION_PREFIX + "worker-abc");
        verify(registry).remove("worker-abc");
    }

    @Test
    void terminate_tmuxFails_stillRemovesFromRegistry() throws Exception {
        doThrow(new java.io.IOException("session not found")).when(tmux).killSession(anyString());

        assertThatNoException().isThrownBy(() -> provisioner.terminate("ghost-worker"));
        verify(registry).remove("ghost-worker");
    }

    @Test
    void getCapabilities_returnsConfiguredCapabilities() {
        assertThat(provisioner.getCapabilities()).contains("code-reviewer");
        assertThat(provisioner.getCapabilities()).doesNotContain("default");
    }

    private ProvisionContext provisionContext(UUID caseId) {
        var wc = new WorkerContext("task", caseId, null, List.of(),
                PropagationContext.createRoot(), Map.of());
        return new ProvisionContext(caseId, "code-reviewer", wc, PropagationContext.createRoot());
    }
}
