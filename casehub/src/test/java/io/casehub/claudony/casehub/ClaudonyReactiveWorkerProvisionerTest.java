package io.casehub.claudony.casehub;

import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.model.Session;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.ProvisioningException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudonyReactiveWorkerProvisionerTest {

    private TmuxService tmux;
    private SessionRegistry registry;
    private WorkerCommandResolver resolver;
    private WorkerSessionMapping sessionMapping;
    private ClaudonyReactiveWorkerProvisioner provisioner;

    @BeforeEach
    void setUp() {
        tmux = mock(TmuxService.class);
        registry = mock(SessionRegistry.class);
        sessionMapping = new WorkerSessionMapping();
        resolver = new WorkerCommandResolver(Map.of("code-reviewer", "claude", "default", "claude"));
        provisioner = new ClaudonyReactiveWorkerProvisioner(true, tmux, registry, resolver, sessionMapping, "/tmp/workers");
    }

    @Test
    void provision_createsSessionAndRegistersWorker() throws Exception {
        var caseId = UUID.randomUUID();

        ProvisionResult result = provisioner.provision(Set.of("code-reviewer"), provisionContext(caseId))
                .await()
                .indefinitely();

        assertThat(result).isNotNull();
        verify(tmux).createSession(contains(ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX), eq("/tmp/workers"), eq("claude"));
        verify(registry).register(any(Session.class));
    }

    @Test
    void provision_registersRoleToSessionMapping() throws Exception {
        var caseId = UUID.randomUUID();

        provisioner.provision(Set.of("code-reviewer"), provisionContext(caseId))
                .await()
                .indefinitely();

        assertThat(sessionMapping.findByRole("code-reviewer")).isPresent();
        assertThat(sessionMapping.findByCase(caseId.toString(), "code-reviewer")).isPresent();
    }

    @Test
    void provision_disabled_failsWithProvisioningException() {
        var disabledProvisioner = new ClaudonyReactiveWorkerProvisioner(
                false, tmux, registry, resolver, sessionMapping, "/tmp");

        assertThatThrownBy(() -> disabledProvisioner.provision(Set.of("code-reviewer"), provisionContext(UUID.randomUUID()))
                .await()
                .indefinitely())
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void provision_tmuxFails_failsWithProvisioningException() throws Exception {
        doThrow(new java.io.IOException("tmux not found")).when(tmux)
                .createSession(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> provisioner.provision(Set.of("code-reviewer"), provisionContext(UUID.randomUUID()))
                .await()
                .indefinitely())
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Failed to create tmux session");
    }

    @Test
    void provision_stampsSessionWithCaseIdAndRoleName() throws Exception {
        var caseId = UUID.randomUUID();

        provisioner.provision(Set.of("code-reviewer"), provisionContext(caseId))
                .await()
                .indefinitely();

        var captor = ArgumentCaptor.forClass(Session.class);
        verify(registry).register(captor.capture());
        var session = captor.getValue();
        assertThat(session.caseId()).contains(caseId.toString());
        assertThat(session.roleName()).contains("code-reviewer");
    }

    @Test
    void terminate_killsSessionAndRemovesFromRegistry() throws Exception {
        provisioner.terminate("worker-abc")
                .await()
                .indefinitely();

        verify(tmux).killSession(ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX + "worker-abc");
        verify(registry).remove("worker-abc");
    }

    @Test
    void terminate_tmuxFails_stillRemovesFromRegistry() throws Exception {
        doThrow(new java.io.IOException("session not found")).when(tmux).killSession(anyString());

        assertThatNoException().isThrownBy(() -> provisioner.terminate("ghost-worker")
                .await()
                .indefinitely());
        verify(registry).remove("ghost-worker");
    }

    @Test
    void getCapabilities_returnsResolverCapabilities() {
        var capabilities = provisioner.getCapabilities()
                .await()
                .indefinitely();

        assertThat(capabilities).contains("code-reviewer");
        assertThat(capabilities).doesNotContain("default");
    }

    private ProvisionContext provisionContext(UUID caseId) {
        return new ProvisionContext(caseId, "code-reviewer", null, null, null, null);
    }
}
