package io.casehub.claudony.casehub.fleet;

import io.casehub.claudony.server.TmuxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class FleetPoolIntegrationTest {

    private static final String TEST_PREFIX = "test-fleet-";

    private TmuxService tmux;
    private String mockAgentCommand;
    private Path mockAgentScript;
    private final List<String> createdSessions = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        tmux            = new TmuxService();
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
    }

    @AfterEach
    void tearDown() throws Exception {
        for (String sessionId : createdSessions) {
            try {tmux.killSession(sessionId);} catch (Exception ignored) {}
        }
        for (String name : tmux.listSessionNames()) {
            if (name.startsWith(TEST_PREFIX)) {
                try {tmux.killSession(name);} catch (Exception ignored) {}
            }
        }
        Files.deleteIfExists(mockAgentScript);
    }

    @Test
    void fullChain_yamlToRegistryToBackendToSession() throws Exception {
        var yaml = """
                agent-pools:
                  test-reviewer:
                    working-dir: /tmp
                    command: "%s"
                    pool:
                      min-active: 0
                      max-active: 5
                """.formatted(mockAgentCommand);

        var parser = new AgentPoolYamlParser();
        var registry = new AgentPoolDefinitionRegistry();
        parser.parseInto(yaml, registry);

        assertThat(registry.get("test-reviewer")).isPresent();
        var definition = registry.get("test-reviewer").get();

        var ops = new TmuxSessionOperations(tmux, TEST_PREFIX, definition.agent().command());
        var manager = new AgentSessionManager(definition.toSessionManagerConfig(), ops);

        var session = manager.acquireSession("reviewer-1", "/tmp");
        createdSessions.add(session.instanceId());

        assertThat(tmux.sessionExists(session.instanceId())).isTrue();

        Thread.sleep(1000);
        String output = tmux.capturePane(session.instanceId(), 20);
        assertThat(output).contains("MOCK_AGENT_READY");

        manager.destroySession(session.instanceId());
        assertThat(tmux.sessionExists(session.instanceId())).isFalse();
    }

    @Test
    void poolCapacity_suspendsWhenFull() throws Exception {
        var definition = AgentPoolDefinition.builder()
                .agent("capacity-test")
                    .command(mockAgentCommand)
                .pool()
                    .minActive(0)
                    .maxActive(2)
                .build();

        var ops = new TmuxSessionOperations(tmux, TEST_PREFIX, definition.agent().command());
        var manager = new AgentSessionManager(definition.toSessionManagerConfig(), ops);

        var s1 = manager.acquireSession("worker-1", "/tmp");
        createdSessions.add(s1.instanceId());
        var s2 = manager.acquireSession("worker-2", "/tmp/other");
        createdSessions.add(s2.instanceId());

        assertThat(manager.activeCount()).isEqualTo(2);

        var s3 = manager.acquireSession("worker-3", "/tmp/third");
        createdSessions.add(s3.instanceId());

        assertThat(manager.activeCount()).isEqualTo(2);
        assertThat(manager.status().idle()).isEqualTo(1);
        assertThat(manager.status().total()).isEqualTo(3);
    }

    @Test
    void poolStatus_reflectsRealState() throws Exception {
        var definition = AgentPoolDefinition.builder()
                .agent("status-test")
                    .command(mockAgentCommand)
                .pool()
                    .minActive(0)
                    .maxActive(5)
                .build();

        var ops = new TmuxSessionOperations(tmux, TEST_PREFIX, definition.agent().command());
        var manager = new AgentSessionManager(definition.toSessionManagerConfig(), ops);

        assertThat(manager.status().active()).isZero();

        var session = manager.acquireSession("worker-1", "/tmp");
        createdSessions.add(session.instanceId());
        assertThat(manager.status().active()).isEqualTo(1);

        manager.destroySession(session.instanceId());
        assertThat(manager.status().active()).isZero();
    }

    @Test
    void inputOutput_sendKeysDeliversToProcess() throws Exception {
        var definition = AgentPoolDefinition.builder()
                                            .agent("io-test")
                                            .command(mockAgentCommand)
                                            .pool()
                                            .minActive(0)
                                            .maxActive(5)
                                            .build();

        var ops     = new TmuxSessionOperations(tmux, TEST_PREFIX, definition.agent().command());
        var manager = new AgentSessionManager(definition.toSessionManagerConfig(), ops);

        var session = manager.acquireSession("worker-1", "/tmp");
        createdSessions.add(session.instanceId());

        Thread.sleep(1000);
        String readyOutput = tmux.capturePane(session.instanceId(), 20);
        assertThat(readyOutput).contains("MOCK_AGENT_READY");

        tmux.sendKeys(session.instanceId(), "hello");
        tmux.sendRawKeys(session.instanceId(), "Enter");

        Thread.sleep(500);
        String echoOutput = tmux.capturePane(session.instanceId(), 20);
        assertThat(echoOutput).contains("ECHO:hello");

        manager.destroySession(session.instanceId());
    }

    @Test
    void resumeSession_createsNewTmuxSession() throws Exception {
        var definition = AgentPoolDefinition.builder()
                                            .agent("resume-test")
                                            .command(mockAgentCommand)
                                            .pool()
                                            .minActive(0)
                                            .maxActive(5)
                                            .build();

        var ops     = new TmuxSessionOperations(tmux, TEST_PREFIX, definition.agent().command());
        var manager = new AgentSessionManager(definition.toSessionManagerConfig(), ops);

        var session = manager.acquireSession("worker-1", "/tmp");
        createdSessions.add(session.instanceId());
        assertThat(tmux.sessionExists(session.instanceId())).isTrue();

        manager.suspendSession(session.instanceId());
        assertThat(tmux.sessionExists(session.instanceId())).isFalse();
        assertThat(session.state()).isEqualTo(SessionState.SUSPENDED);

        var resumed = manager.resumeSession(session.instanceId());
        assertThat(resumed).isNotNull();
        assertThat(resumed.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(tmux.sessionExists(session.instanceId())).isTrue();

        Thread.sleep(1000);
        String output = tmux.capturePane(session.instanceId(), 20);
        assertThat(output).contains("MOCK_AGENT_READY");

        manager.destroySession(session.instanceId());
    }

    @Test
    void memoryBytes_readsRealProcessMemory() throws Exception {
        var definition = AgentPoolDefinition.builder()
                                            .agent("memory-test")
                                            .command(mockAgentCommand)
                                            .pool()
                                            .minActive(0)
                                            .maxActive(5)
                                            .build();

        var ops     = new TmuxSessionOperations(tmux, TEST_PREFIX, definition.agent().command());
        var manager = new AgentSessionManager(definition.toSessionManagerConfig(), ops);

        var session = manager.acquireSession("worker-1", "/tmp");
        createdSessions.add(session.instanceId());

        Thread.sleep(1000);
        long memory = ops.memoryBytes(session.instanceId());
        assertThat(memory).isGreaterThan(0);

        manager.destroySession(session.instanceId());
    }

    @Test
    void concurrentAcquire_noLeakedSessions() throws Exception {
        var definition = AgentPoolDefinition.builder()
                                            .agent("concurrent-test")
                                            .command(mockAgentCommand)
                                            .pool()
                                            .minActive(0)
                                            .maxActive(5)
                                            .build();

        var ops     = new TmuxSessionOperations(tmux, TEST_PREFIX, definition.agent().command());
        var manager = new AgentSessionManager(definition.toSessionManagerConfig(), ops);

        var latch    = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            Future<ManagedSession> f1 = executor.submit(() -> {
                latch.await();
                return manager.acquireSession("worker-1", "/tmp/a");
            });
            Future<ManagedSession> f2 = executor.submit(() -> {
                latch.await();
                return manager.acquireSession("worker-2", "/tmp/b");
            });

            latch.countDown();

            var s1 = f1.get();
            var s2 = f2.get();
            createdSessions.add(s1.instanceId());
            createdSessions.add(s2.instanceId());

            assertThat(manager.activeCount()).isEqualTo(2);
            assertThat(tmux.sessionExists(s1.instanceId())).isTrue();
            assertThat(tmux.sessionExists(s2.instanceId())).isTrue();
            assertThat(s1.instanceId()).isNotEqualTo(s2.instanceId());
        } finally {
            executor.shutdown();
        }

        manager.shutdown();
    }


}
