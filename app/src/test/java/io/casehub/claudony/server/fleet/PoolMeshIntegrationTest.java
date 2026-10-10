package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.ManagedSession;
import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PoolMeshIntegrationTest {

    private InMemoryRegistryService registryService;
    private PoolMeshRegistrar       registrar;

    @BeforeEach
    void setUp() {
        registryService = new InMemoryRegistryService(event -> {});
        registrar       = new PoolMeshRegistrar(registryService);
    }

    private ManagedSession session(String id, String identity) {
        return new ManagedSession(id, identity, "/tmp", null);
    }

    private void registerAgentInstance(String instanceId) {
        var now = java.time.Instant.now();
        registryService.register(new RegistryEntry(
                instanceId, "agent-instance", "default", "default",
                java.util.Map.of("description", "test"),
                now, now, java.time.Duration.ofMinutes(5), HealthStatus.HEALTHY));
    }

    @Test
    void acquire_linksPoolToSession() {
        var session = session("session-1", "reviewer-1");
        registrar.onAcquired(session, "code-reviewer");

        var rels = registryService.relationships("code-reviewer");
        assertThat(rels).hasSize(1);
        assertThat(rels.get(0).sourceId()).isEqualTo("code-reviewer");
        assertThat(rels.get(0).targetId()).isEqualTo("session-1");
        assertThat(rels.get(0).type()).isEqualTo("contains");
    }

    @Test
    void suspend_updatesHealthToDegraded() {
        var session = session("session-1", "reviewer-1");
        registerAgentInstance("session-1");

        registrar.onSuspended(session, "code-reviewer");

        var entry = registryService.resolve("session-1");
        assertThat(entry).isPresent();
        assertThat(entry.get().health()).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void resume_updatesHealthToHealthy() {
        var session = session("session-1", "reviewer-1");
        registerAgentInstance("session-1");
        registrar.onSuspended(session, "code-reviewer");

        registrar.onResumed(session, "code-reviewer");

        var entry = registryService.resolve("session-1");
        assertThat(entry).isPresent();
        assertThat(entry.get().health()).isEqualTo(HealthStatus.HEALTHY);
    }

    @Test
    void destroy_unlinksPoolFromSession() {
        var session = session("session-1", "reviewer-1");
        registrar.onAcquired(session, "code-reviewer");
        assertThat(registryService.relationships("code-reviewer")).hasSize(1);

        registrar.onDestroyed("session-1", "code-reviewer");
        assertThat(registryService.relationships("code-reviewer")).isEmpty();
    }

    @Test
    void fullLifecycle_cleanState() {
        var session = session("session-1", "reviewer-1");
        registerAgentInstance("session-1");

        registrar.onAcquired(session, "code-reviewer");
        assertThat(registryService.relationships("code-reviewer")).hasSize(1);
        assertThat(registryService.resolve("session-1").get().health()).isEqualTo(HealthStatus.HEALTHY);

        registrar.onSuspended(session, "code-reviewer");
        assertThat(registryService.resolve("session-1").get().health()).isEqualTo(HealthStatus.DEGRADED);

        registrar.onResumed(session, "code-reviewer");
        assertThat(registryService.resolve("session-1").get().health()).isEqualTo(HealthStatus.HEALTHY);

        registrar.onDestroyed("session-1", "code-reviewer");
        assertThat(registryService.relationships("code-reviewer")).isEmpty();
    }

    @Test
    void multipleSessionsInPool_allLinked() {
        var s1 = session("session-1", "reviewer-1");
        var s2 = session("session-2", "reviewer-2");

        registrar.onAcquired(s1, "code-reviewer");
        registrar.onAcquired(s2, "code-reviewer");

        var rels = registryService.relationships("code-reviewer");
        assertThat(rels).hasSize(2);
        assertThat(rels).extracting("targetId")
                        .containsExactlyInAnyOrder("session-1", "session-2");
    }

    @Test
    void suspend_noOpWhenSessionNotInRegistry() {
        var session = session("session-1", "reviewer-1");
        registrar.onSuspended(session, "code-reviewer");
        assertThat(registryService.resolve("session-1")).isEmpty();
    }
}
