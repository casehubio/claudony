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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudonyReactiveWorkerProvisionerTest {

    private TmuxService tmux;
    private SessionRegistry registry;
    private ProviderConfigSource configSource;
    private WorkerSessionMapping sessionMapping;
    private ClaudonyReactiveWorkerProvisioner provisioner;

    @BeforeEach
    void setUp() {
        tmux = mock(TmuxService.class);
        registry = mock(SessionRegistry.class);
        sessionMapping = new WorkerSessionMapping();
        configSource = new ProviderConfigSource() {
            @Override
            public ClaudonyProviderConfig forAgent(String agentId) {
                if ("code-reviewer".equals(agentId)) {
                    return new ClaudonyProviderConfig(
                            Optional.of("claude"), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty());
                }
                return ClaudonyProviderConfig.EMPTY;
            }

            @Override
            public Set<String> declaredAgentIds() {
                return Set.of("code-reviewer");
            }
        };
        provisioner = new ClaudonyReactiveWorkerProvisioner(true, tmux, registry, configSource, sessionMapping, "claude", "/tmp/workers", null, null, null);
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
                false, tmux, registry, configSource, sessionMapping, "claude", "/tmp", null, null, null);

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
    void getCapabilities_returnsDeclaredAgentIds() {
        var capabilities = provisioner.getCapabilities()
                .await()
                .indefinitely();

        assertThat(capabilities).containsExactly("code-reviewer");
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

        provisioner.seedCausalContextForTest(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, caseId, entryId);

        assertThat(provisioner.drainCausalContext(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, caseId)).isEqualTo(entryId);
    }

    @Test
    void drainCausalContext_withoutSeed_returnsNull() {
        assertThat(provisioner.drainCausalContext(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, UUID.randomUUID())).isNull();
    }

    @Test
    void drainCausalContext_isDraining_secondCallReturnsNull() {
        UUID caseId = UUID.randomUUID();
        provisioner.seedCausalContextForTest(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, caseId, UUID.randomUUID());

        provisioner.drainCausalContext(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, caseId);

        assertThat(provisioner.drainCausalContext(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, caseId)).isNull();
    }

    @Test
    void provision_withTriggerFields_storesCausalContextAndReturnsEntryId() throws Exception {
        UUID entryId = UUID.randomUUID();
        QhorusCausalLinkResolver mockResolver = mock(QhorusCausalLinkResolver.class);
        when(mockResolver.resolve("ch-123", "corr-456"))
            .thenReturn(Uni.createFrom().item(Optional.of(entryId)));
        var prov = new ClaudonyReactiveWorkerProvisioner(
            true, tmux, registry, configSource, sessionMapping, "claude", "/tmp/workers", null, null, mockResolver);
        UUID caseId = UUID.randomUUID();
        var ctx = new ProvisionContext(caseId, io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, "code-reviewer", null, null, "ch-123", "corr-456");

        ProvisionResult result = prov.provision(Set.of("code-reviewer"), ctx)
            .await().indefinitely();

        assertThat(result.causedByEntryId()).isEqualTo(entryId);
        assertThat(prov.drainCausalContext(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, caseId)).isEqualTo(entryId);
        assertThat(prov.drainCausalContext(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, caseId)).isNull(); // drained — second call returns null
    }

    @Test
    void provision_withNullTriggerFields_guardShortCircuits() throws Exception {
        QhorusCausalLinkResolver mockResolver = mock(QhorusCausalLinkResolver.class);
        var prov = new ClaudonyReactiveWorkerProvisioner(
            true, tmux, registry, configSource, sessionMapping, "claude", "/tmp/workers", null, null, mockResolver);
        UUID caseId = UUID.randomUUID();
        var ctx = new ProvisionContext(caseId, io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, "code-reviewer", null, null, null, null);

        ProvisionResult result = prov.provision(Set.of("code-reviewer"), ctx)
            .await().indefinitely();

        assertThat(result.causedByEntryId()).isNull();
        assertThat(prov.drainCausalContext(io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, caseId)).isNull();
        // null trigger fields → guard short-circuits before resolver is called
        verifyNoInteractions(mockResolver);
    }

    @Test
    void provision_withProviderConfig_tmuxReceivesEnrichedCommand() throws Exception {
        ProviderConfigSource richSource = new ProviderConfigSource() {
            @Override
            public ClaudonyProviderConfig forAgent(String agentId) {
                return new ClaudonyProviderConfig(
                        Optional.of("claude"), Optional.of("opus"), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty());
            }

            @Override
            public Set<String> declaredAgentIds() {
                return Set.of("code-reviewer");
            }
        };
        var prov = new ClaudonyReactiveWorkerProvisioner(
                true, tmux, registry, richSource, sessionMapping, "claude", "/tmp/workers", null, null, null);

        prov.provision(Set.of("code-reviewer"), provisionContext(UUID.randomUUID()))
                .await().indefinitely();

        var captor = ArgumentCaptor.forClass(String.class);
        verify(tmux).createWorkerSession(anyString(), anyString(), captor.capture());
        assertThat(captor.getValue()).contains("--model 'opus'");
    }

    @Test
    void provision_withWorkingDirOverride_tmuxReceivesOverriddenDir() throws Exception {
        ProviderConfigSource dirSource = new ProviderConfigSource() {
            @Override
            public ClaudonyProviderConfig forAgent(String agentId) {
                return new ClaudonyProviderConfig(
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.of("/custom/workspace"));
            }

            @Override
            public Set<String> declaredAgentIds() {
                return Set.of("code-reviewer");
            }
        };
        var prov = new ClaudonyReactiveWorkerProvisioner(
                true, tmux, registry, dirSource, sessionMapping, "claude", "/tmp/workers", null, null, null);

        prov.provision(Set.of("code-reviewer"), provisionContext(UUID.randomUUID()))
                .await().indefinitely();

        var captor = ArgumentCaptor.forClass(String.class);
        verify(tmux).createWorkerSession(anyString(), captor.capture(), anyString());
        assertThat(captor.getValue()).isEqualTo("/custom/workspace");
    }

    @Test
    void provision_withNoPerAgentConfig_usesDefaultCommand() throws Exception {
        ProviderConfigSource emptySource = new ProviderConfigSource() {
            @Override
            public ClaudonyProviderConfig forAgent(String agentId) {
                return ClaudonyProviderConfig.EMPTY;
            }

            @Override
            public Set<String> declaredAgentIds() {
                return Set.of();
            }
        };
        var prov = new ClaudonyReactiveWorkerProvisioner(
                true, tmux, registry, emptySource, sessionMapping, "claude", "/tmp/workers", null, null, null);

        prov.provision(Set.of("unknown-agent"), provisionContext(UUID.randomUUID()))
                .await().indefinitely();

        var captor = ArgumentCaptor.forClass(String.class);
        verify(tmux).createWorkerSession(anyString(), anyString(), captor.capture());
        assertThat(captor.getValue()).isEqualTo("claude");
    }

    @Test
    void provision_sessionRecordsEffectiveValues() throws Exception {
        ProviderConfigSource richSource = new ProviderConfigSource() {
            @Override
            public ClaudonyProviderConfig forAgent(String agentId) {
                return new ClaudonyProviderConfig(
                        Optional.of("claude"), Optional.of("opus"), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.of("/effective/dir"));
            }

            @Override
            public Set<String> declaredAgentIds() {
                return Set.of("code-reviewer");
            }
        };
        var prov = new ClaudonyReactiveWorkerProvisioner(
                true, tmux, registry, richSource, sessionMapping, "claude", "/tmp/workers", null, null, null);

        prov.provision(Set.of("code-reviewer"), provisionContext(UUID.randomUUID()))
                .await().indefinitely();

        var captor = ArgumentCaptor.forClass(Session.class);
        verify(registry).register(captor.capture());
        var session = captor.getValue();
        assertThat(session.workingDir()).isEqualTo("/effective/dir");
        assertThat(session.command()).contains("--model 'opus'");
    }

    private ProvisionContext provisionContext(UUID caseId) {
        return new ProvisionContext(caseId, io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID, "code-reviewer", null, null, null, null);
    }
}
