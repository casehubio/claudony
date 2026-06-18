package io.casehub.claudony.casehub;

import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.model.Session;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.ProvisioningException;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import java.util.Map;
import java.util.Optional;
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
        provisioner = new ClaudonyReactiveWorkerProvisioner(true, tmux, registry, resolver, sessionMapping, "/tmp/workers", null, null, null);
    }

    @Test
    void provision_createsWorkerSessionAndRegistersWorker() throws Exception {
        var caseId = UUID.randomUUID();

        ProvisionResult result = provisioner.provision(Set.of("code-reviewer"), provisionContext(caseId))
                .await()
                .indefinitely();

        assertThat(result).isNotNull();
        verify(tmux).createWorkerSession(
                contains(ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX), eq("/tmp/workers"), eq("claude"));
        verify(registry).register(any(Session.class));
    }

    @Test
    void provision_setsCasehubTmuxOptions_afterSessionCreation() throws Exception {
        var caseId = UUID.randomUUID();

        provisioner.provision(Set.of("code-reviewer"), provisionContext(caseId))
                .await()
                .indefinitely();

        // caseId and roleName must be persisted to tmux options for recovery after restart
        verify(tmux).setSessionOption(
                contains(ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX),
                eq("@casehub_case_id"),
                eq(caseId.toString()));
        verify(tmux).setSessionOption(
                contains(ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX),
                eq("@casehub_role"),
                eq("code-reviewer"));
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
                false, tmux, registry, resolver, sessionMapping, "/tmp", null, null, null);

        assertThatThrownBy(() -> disabledProvisioner.provision(Set.of("code-reviewer"), provisionContext(UUID.randomUUID()))
                .await()
                .indefinitely())
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void provision_tmuxFails_failsWithProvisioningException() throws Exception {
        doThrow(new java.io.IOException("tmux not found")).when(tmux)
                .createWorkerSession(anyString(), anyString(), anyString());

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
    void terminate_removesFromRegistryFirst_thenKillsSession() throws Exception {
        // Order matters: registry.remove() is the watcher cancellation signal.
        // It must happen BEFORE tmux.killSession() so the watcher sees the session
        // absent in the registry and stops without publishing a false completion.
        provisioner.terminate("worker-abc", null)
                .await()
                .indefinitely();

        InOrder inOrder = inOrder(registry, tmux);
        inOrder.verify(registry).remove("worker-abc");
        inOrder.verify(tmux).killSession(ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX + "worker-abc");
    }

    @Test
    void terminate_tmuxFails_stillRemovesFromRegistry() throws Exception {
        doThrow(new java.io.IOException("session not found")).when(tmux).killSession(anyString());

        assertThatNoException().isThrownBy(() -> provisioner.terminate("ghost-worker", null)
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

    @Test
    void provision_withNullTriggerFields_returnsEmptyProvisionResult() throws Exception {
        var result = provisioner.provision(Set.of("code-reviewer"), provisionContext(UUID.randomUUID()))
                .await()
                .indefinitely();

        assertThat(result.causedByEntryId()).isNull();
    }

    @Test
    void drainCausalContext_afterSeed_returnsSeededValue() {
        UUID caseId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();

        provisioner.seedCausalContextForTest(caseId, entryId);

        assertThat(provisioner.drainCausalContext(caseId)).isEqualTo(entryId);
    }

    @Test
    void drainCausalContext_withoutSeed_returnsNull() {
        assertThat(provisioner.drainCausalContext(UUID.randomUUID())).isNull();
    }

    @Test
    void drainCausalContext_isDraining_secondCallReturnsNull() {
        UUID caseId = UUID.randomUUID();
        provisioner.seedCausalContextForTest(caseId, UUID.randomUUID());

        provisioner.drainCausalContext(caseId);

        assertThat(provisioner.drainCausalContext(caseId)).isNull();
    }

    @Test
    void provision_withTriggerFields_storesCausalContextAndReturnsEntryId() throws Exception {
        UUID entryId = UUID.randomUUID();
        QhorusCausalLinkResolver mockResolver = mock(QhorusCausalLinkResolver.class);
        when(mockResolver.resolve("ch-123", "corr-456"))
            .thenReturn(Uni.createFrom().item(Optional.of(entryId)));
        var prov = new ClaudonyReactiveWorkerProvisioner(
            true, tmux, registry, resolver, sessionMapping, "/tmp/workers", null, null, mockResolver);
        UUID caseId = UUID.randomUUID();
        var ctx = new ProvisionContext(caseId, null, "code-reviewer", null, null, "ch-123", "corr-456");

        ProvisionResult result = prov.provision(Set.of("code-reviewer"), ctx)
            .await().indefinitely();

        assertThat(result.causedByEntryId()).isEqualTo(entryId);
        assertThat(prov.drainCausalContext(caseId)).isEqualTo(entryId);
        assertThat(prov.drainCausalContext(caseId)).isNull(); // drained — second call returns null
    }

    @Test
    void provision_withNullTriggerFields_guardShortCircuits() throws Exception {
        QhorusCausalLinkResolver mockResolver = mock(QhorusCausalLinkResolver.class);
        var prov = new ClaudonyReactiveWorkerProvisioner(
            true, tmux, registry, resolver, sessionMapping, "/tmp/workers", null, null, mockResolver);
        UUID caseId = UUID.randomUUID();
        var ctx = new ProvisionContext(caseId, null, "code-reviewer", null, null, null, null);

        ProvisionResult result = prov.provision(Set.of("code-reviewer"), ctx)
            .await().indefinitely();

        assertThat(result.causedByEntryId()).isNull();
        assertThat(prov.drainCausalContext(caseId)).isNull();
        // null trigger fields → guard short-circuits before resolver is called
        verifyNoInteractions(mockResolver);
    }

    private ProvisionContext provisionContext(UUID caseId) {
        return new ProvisionContext(caseId, null, "code-reviewer", null, null, null, null);
    }
}
