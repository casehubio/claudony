package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.AgentSessionManager;
import io.casehub.claudony.casehub.fleet.AgentSessionManagerConfig;
import io.casehub.claudony.casehub.fleet.TmuxSessionOperations;
import io.casehub.claudony.server.TmuxService;
import io.casehub.qhorus.persistence.memory.InMemoryInstanceStore;
import io.casehub.qhorus.runtime.instance.InstanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PoolMeshIntegrationTest {

    private static final String TEST_PREFIX = "test-mesh-";

    private TmuxService tmux;
    private String mockAgentCommand;
    private Path mockAgentScript;
    private final List<String> createdSessions = new ArrayList<>();
    private InMemoryInstanceStore instanceStore;
    private InstanceService instanceService;

    @BeforeEach
    void setUp() throws IOException {
        tmux = new TmuxService();
        mockAgentScript = Files.createTempFile("mock-agent-", ".sh");
        Files.writeString(mockAgentScript, """
                #!/bin/sh
                echo MOCK_AGENT_READY
                while IFS= read -r line; do
                    echo "ECHO:$line"
                done
                """);
        mockAgentScript.toFile().setExecutable(true);
        mockAgentCommand = mockAgentScript.toAbsolutePath().toString();
        instanceStore = new InMemoryInstanceStore();
        instanceService = new InstanceService(instanceStore);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (String sessionId : createdSessions) {
            try { tmux.killSession(sessionId); } catch (Exception ignored) {}
        }
        for (String name : tmux.listSessionNames()) {
            if (name.startsWith(TEST_PREFIX)) {
                try { tmux.killSession(name); } catch (Exception ignored) {}
            }
        }
        Files.deleteIfExists(mockAgentScript);
    }

    @Test
    void acquire_registersAsQhorusInstance() {
        var registrar = new PoolMeshRegistrar(instanceService);
        var ops = new TmuxSessionOperations(tmux, TEST_PREFIX, mockAgentCommand);
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), ops, registrar, "code-reviewer");

        var session = manager.acquireSession("reviewer-1", "/tmp");
        createdSessions.add(session.instanceId());

        var instance = instanceService.findByInstanceId(session.instanceId());
        assertThat(instance).isPresent();
        assertThat(instance.get().status()).isEqualTo("online");
        assertThat(instance.get().description()).isEqualTo("pool:code-reviewer/reviewer-1");
        assertThat(instance.get().claudonySessionId()).isEqualTo(session.instanceId());

        var caps = instanceService.findCapabilityTagsForInstance(session.instanceId());
        assertThat(caps).containsExactly("pool:code-reviewer");

        manager.destroySession(session.instanceId());
    }

    @Test
    void suspend_marksInstanceOffline() {
        var registrar = new PoolMeshRegistrar(instanceService);
        var ops = new TmuxSessionOperations(tmux, TEST_PREFIX, mockAgentCommand);
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), ops, registrar, "code-reviewer");

        var session = manager.acquireSession("reviewer-1", "/tmp");
        createdSessions.add(session.instanceId());

        manager.suspendSession(session.instanceId());

        var instance = instanceService.findByInstanceId(session.instanceId());
        assertThat(instance).isPresent();
        assertThat(instance.get().status()).isEqualTo("offline");

        manager.destroySession(session.instanceId());
    }

    @Test
    void resume_marksInstanceOnline() {
        var registrar = new PoolMeshRegistrar(instanceService);
        var ops = new TmuxSessionOperations(tmux, TEST_PREFIX, mockAgentCommand);
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), ops, registrar, "code-reviewer");

        var session = manager.acquireSession("reviewer-1", "/tmp");
        createdSessions.add(session.instanceId());

        manager.suspendSession(session.instanceId());
        assertThat(instanceService.findByInstanceId(session.instanceId()).get().status())
                .isEqualTo("offline");

        manager.resumeSession(session.instanceId());

        var instance = instanceService.findByInstanceId(session.instanceId());
        assertThat(instance).isPresent();
        assertThat(instance.get().status()).isEqualTo("online");

        manager.destroySession(session.instanceId());
    }

    @Test
    void destroy_deregistersInstance() {
        var registrar = new PoolMeshRegistrar(instanceService);
        var ops = new TmuxSessionOperations(tmux, TEST_PREFIX, mockAgentCommand);
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), ops, registrar, "code-reviewer");

        var session = manager.acquireSession("reviewer-1", "/tmp");
        createdSessions.add(session.instanceId());

        assertThat(instanceService.findByInstanceId(session.instanceId())).isPresent();

        manager.destroySession(session.instanceId());

        assertThat(instanceService.findByInstanceId(session.instanceId())).isEmpty();
    }

    @Test
    void fullLifecycle_cleanState() {
        var registrar = new PoolMeshRegistrar(instanceService);
        var ops = new TmuxSessionOperations(tmux, TEST_PREFIX, mockAgentCommand);
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), ops, registrar, "code-reviewer");

        var session = manager.acquireSession("reviewer-1", "/tmp");
        createdSessions.add(session.instanceId());
        String id = session.instanceId();

        assertThat(instanceService.findByInstanceId(id).get().status()).isEqualTo("online");

        manager.suspendSession(id);
        assertThat(instanceService.findByInstanceId(id).get().status()).isEqualTo("offline");

        manager.resumeSession(id);
        assertThat(instanceService.findByInstanceId(id).get().status()).isEqualTo("online");

        manager.destroySession(id);
        assertThat(instanceService.findByInstanceId(id)).isEmpty();
        assertThat(instanceService.listAll()).isEmpty();
    }

    @Test
    void capabilityRouting_findsPoolInstances() {
        var registrar = new PoolMeshRegistrar(instanceService);

        var ops1 = new TmuxSessionOperations(tmux, TEST_PREFIX, mockAgentCommand);
        var mgr1 = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), ops1, registrar, "code-reviewer");
        var s1 = mgr1.acquireSession("reviewer-1", "/tmp");
        createdSessions.add(s1.instanceId());

        var ops2 = new TmuxSessionOperations(tmux, TEST_PREFIX + "r-", mockAgentCommand);
        var mgr2 = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), ops2, registrar, "test-runner");
        var s2 = mgr2.acquireSession("runner-1", "/tmp/runner");
        createdSessions.add(s2.instanceId());

        var reviewers = instanceService.findByCapability("pool:code-reviewer");
        assertThat(reviewers).hasSize(1);
        assertThat(reviewers.get(0).instanceId()).isEqualTo(s1.instanceId());

        var runners = instanceService.findByCapability("pool:test-runner");
        assertThat(runners).hasSize(1);
        assertThat(runners.get(0).instanceId()).isEqualTo(s2.instanceId());

        mgr1.destroySession(s1.instanceId());
        mgr2.destroySession(s2.instanceId());
    }

    @Test
    void multipleSessionsInPool_allRegistered() {
        var registrar = new PoolMeshRegistrar(instanceService);
        var ops = new TmuxSessionOperations(tmux, TEST_PREFIX, mockAgentCommand);
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), ops, registrar, "code-reviewer");

        var s1 = manager.acquireSession("reviewer-1", "/tmp");
        var s2 = manager.acquireSession("reviewer-2", "/tmp/other");
        createdSessions.add(s1.instanceId());
        createdSessions.add(s2.instanceId());

        var instances = instanceService.findByCapability("pool:code-reviewer");
        assertThat(instances).hasSize(2);
        assertThat(instances).extracting("instanceId")
                .containsExactlyInAnyOrder(s1.instanceId(), s2.instanceId());

        manager.destroySession(s1.instanceId());
        manager.destroySession(s2.instanceId());
    }
}
