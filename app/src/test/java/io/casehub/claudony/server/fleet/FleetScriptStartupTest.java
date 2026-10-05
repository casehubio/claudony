package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.script.FleetScriptResult;
import io.casehub.claudony.casehub.fleet.script.NodeResult;
import io.casehub.claudony.config.ClaudonyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class FleetScriptStartupTest {

    private FleetScriptStartup startup;
    private List<String> executedYamls;
    private boolean shouldFail;

    @BeforeEach
    void setUp() {
        executedYamls = new ArrayList<>();
        shouldFail = false;

        startup = new FleetScriptStartup();
        startup.config = stubConfig("server");
        startup.fleetScriptService = yaml -> {
            executedYamls.add(yaml);
            if (shouldFail) {
                return new FleetScriptResult(List.of(
                        NodeResult.failed("node", "pool", "test failure")));
            }
            return new FleetScriptResult(List.of(
                    NodeResult.ok("node", "pool", "created")));
        };
    }

    @Test
    void discoversAndExecutesYamlFromClasspath() {
        startup.executeFleetScripts(getClass().getClassLoader());

        assertThat(executedYamls).hasSize(1);
        assertThat(executedYamls.get(0)).contains("startup-test");
    }

    @Test
    void handlesMultipleScripts(@TempDir Path tempDir) throws IOException {
        Path metaInf = tempDir.resolve("META-INF/fleet-scripts");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("first.yaml"), "nodes:\n  a:\n    type: pool\n    spec:\n      agentId: first\n");
        Files.writeString(metaInf.resolve("second.yml"), "nodes:\n  b:\n    type: pool\n    spec:\n      agentId: second\n");

        URLClassLoader cl = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, null);

        startup.executeFleetScripts(cl);

        assertThat(executedYamls).hasSize(2);
    }

    @Test
    void failedScriptDoesNotBlockOthers(@TempDir Path tempDir) throws IOException {
        Path metaInf = tempDir.resolve("META-INF/fleet-scripts");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("script.yaml"), "nodes:\n  a:\n    type: pool\n    spec:\n      agentId: test\n");

        shouldFail = true;
        URLClassLoader cl = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, null);

        assertThatNoException().isThrownBy(() -> startup.executeFleetScripts(cl));
        assertThat(executedYamls).hasSize(1);
    }

    @Test
    void noScriptsDirectory_noExecution() throws IOException {
        URLClassLoader emptyCl = new URLClassLoader(new URL[]{}, null);

        startup.executeFleetScripts(emptyCl);

        assertThat(executedYamls).isEmpty();
    }

    @Test
    void ignoresNonYamlFiles(@TempDir Path tempDir) throws IOException {
        Path metaInf = tempDir.resolve("META-INF/fleet-scripts");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("readme.txt"), "not a script");
        Files.writeString(metaInf.resolve("script.yaml"), "nodes:\n  a:\n    type: pool\n    spec:\n      agentId: test\n");

        URLClassLoader cl = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, null);

        startup.executeFleetScripts(cl);

        assertThat(executedYamls).hasSize(1);
    }

    @Test
    void exceptionInExecute_logsAndContinues(@TempDir Path tempDir) throws IOException {
        Path metaInf = tempDir.resolve("META-INF/fleet-scripts");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("bad.yaml"), "nodes:\n  a:\n    type: pool\n    spec:\n      agentId: test\n");
        Files.writeString(metaInf.resolve("good.yaml"), "nodes:\n  b:\n    type: pool\n    spec:\n      agentId: test2\n");

        List<String> callOrder = new ArrayList<>();
        startup.fleetScriptService = yaml -> {
            callOrder.add(yaml);
            if (callOrder.size() == 1) {
                throw new RuntimeException("parse error");
            }
            return new FleetScriptResult(List.of(
                    NodeResult.ok("node", "pool", "created")));
        };

        URLClassLoader cl = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, null);

        assertThatNoException().isThrownBy(() -> startup.executeFleetScripts(cl));
        assertThat(callOrder).hasSize(2);
    }

    private static ClaudonyConfig stubConfig(String mode) {
        return new ClaudonyConfig() {
            @Override public String mode() { return mode; }
            @Override public int port() { return 7777; }
            @Override public String bind() { return "localhost"; }
            @Override public String serverUrl() { return "http://localhost:7777"; }
            @Override public String claudeCommand() { return "claude"; }
            @Override public String tmuxPrefix() { return "claudony-"; }
            @Override public String terminal() { return "auto"; }
            @Override public java.util.Optional<String> agentApiKey() { return java.util.Optional.empty(); }
            @Override public String defaultWorkingDir() { return "/tmp"; }
            @Override public String credentialsFile() { return "/tmp/creds.json"; }
            @Override public java.time.Duration sessionTimeout() { return java.time.Duration.ofDays(7); }
            @Override public String sessionExpiryPolicy() { return "user-interaction"; }
            @Override public java.util.Optional<String> fleetKey() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<String> peers() { return java.util.Optional.empty(); }
            @Override public boolean mdnsDiscovery() { return false; }
            @Override public String name() { return "test"; }
            @Override public String meshRefreshStrategy() { return "poll"; }
            @Override public int meshRefreshInterval() { return 3000; }
            @Override public String caseWorkerUpdate() { return "hybrid"; }
            @Override public long caseWorkerHeartbeatMs() { return 30000; }
        };
    }
}
