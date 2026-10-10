package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.AgentPoolDefinitionRegistry;
import io.casehub.claudony.casehub.fleet.AgentPoolManagerRegistry;
import io.casehub.claudony.casehub.fleet.ScalingConfig;
import io.casehub.claudony.casehub.fleet.SessionOperations;
import io.casehub.claudony.casehub.fleet.script.*;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

class FleetScriptServiceTest {

    private AgentPoolDefinitionRegistry defRegistry;
    private AgentPoolManagerRegistry mgrRegistry;
    private Map<String, ChannelCreateRequest> createdChannels;
    private FleetScriptRunner runner;

    @BeforeEach
    void setUp() {
        defRegistry = new AgentPoolDefinitionRegistry(new InMemoryRegistryService(event -> {}));
        mgrRegistry = new AgentPoolManagerRegistry();
        createdChannels = new ConcurrentHashMap<>();

        SessionOperations ops = new SessionOperations() {
            @Override public String create(String identity, String workingDir) {
                return "test-" + UUID.randomUUID().toString().substring(0, 8);
            }
            @Override public String conversationId(String sessionId) { return UUID.randomUUID().toString(); }
            @Override public void suspend(String sessionId) {}
            @Override public void resume(String sessionId, String conversationId, String workingDir) {}
            @Override public void destroy(String sessionId) {}
            @Override public long memoryBytes(String sessionId) { return 0; }
        };

        var poolHandler = new PoolNodeHandler(defRegistry, mgrRegistry, ops);
        var channelHandler = new ChannelNodeHandler(request -> createdChannels.put(request.name(), request));
        runner = new FleetScriptRunner(List.of(poolHandler, channelHandler));
    }

    @Test
    void executesFleetScript_poolsAndChannels() throws IOException {
        String yaml = new String(
                getClass().getResourceAsStream("/test-fleet.yaml").readAllBytes(),
                StandardCharsets.UTF_8);

        var result = runner.execute(yaml);

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.results()).hasSize(4);

        assertThat(mgrRegistry.poolNames()).contains("reviewer", "auditor");
        assertThat(defRegistry.get("reviewer")).isPresent();
        assertThat(defRegistry.get("reviewer").get().pool().minActive()).isEqualTo(0);
        assertThat(defRegistry.get("reviewer").get().pool().maxActive()).isEqualTo(4);
        assertThat(defRegistry.get("auditor")).isPresent();

        assertThat(createdChannels).containsKey("test/fleet/reviews");
        assertThat(createdChannels).containsKey("test/fleet/findings");
        assertThat(createdChannels.get("test/fleet/reviews").semantic()).isEqualTo(ChannelSemantic.APPEND);
    }

    @Test
    void dependencyOrdering_poolsBeforeChannels() throws IOException {
        String yaml = new String(
                getClass().getResourceAsStream("/test-fleet.yaml").readAllBytes(),
                StandardCharsets.UTF_8);

        var result = runner.execute(yaml);

        var poolIndices = result.results().stream()
                .filter(r -> r.type().equals("pool"))
                .map(r -> result.results().indexOf(r))
                .toList();
        var channelIndices = result.results().stream()
                .filter(r -> r.type().equals("channel"))
                .map(r -> result.results().indexOf(r))
                .toList();

        for (int pi : poolIndices) {
            for (int ci : channelIndices) {
                assertThat(pi).isLessThan(ci);
            }
        }
    }

    @Test
    void unknownNodeType_skipped() {
        String yaml = """
                nodes:
                  agent-node:
                    type: agent
                    spec:
                      agentId: code-reviewer
                  pool-node:
                    type: pool
                    spec:
                      agentId: worker
                """;

        var result = runner.execute(yaml);

        assertThat(result.allSucceeded()).isTrue();
        var agentResult = result.results().stream()
                .filter(r -> r.name().equals("agent-node")).findFirst().orElseThrow();
        assertThat(agentResult.message()).contains("skipped");
        assertThat(mgrRegistry.poolNames()).contains("worker");
    }

    @Test
    void variableSubstitution_appliedToSpecs() {
        String yaml = """
                variables:
                  ws: /custom/workspace
                nodes:
                  my-pool:
                    type: pool
                    spec:
                      agentId: test-agent
                      workingDir: ${var.ws}
                """;

        var result = runner.execute(yaml);

        assertThat(result.allSucceeded()).isTrue();
        var def = defRegistry.get("test-agent").orElseThrow();
        assertThat(def.agent().workingDir()).isEqualTo("/custom/workspace");
    }

    @Test
    void scalingConfig_parsedFromSpec() {
        String yaml = """
                nodes:
                  scaled-pool:
                    type: pool
                    spec:
                      agentId: scaled
                      scaling:
                        type: target-tracking
                        target: 0.7
                        cooldown: 30s
                """;

        var result = runner.execute(yaml);

        assertThat(result.allSucceeded()).isTrue();
        var def = defRegistry.get("scaled").orElseThrow();
        assertThat(def.pool().scaling()).isInstanceOf(ScalingConfig.TargetTrackingConfig.class);
        var ttConfig = (ScalingConfig.TargetTrackingConfig) def.pool().scaling();
        assertThat(ttConfig.targetFillRatio()).isEqualTo(0.7);
    }
}
