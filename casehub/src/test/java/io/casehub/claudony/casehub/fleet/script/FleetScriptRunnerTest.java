package io.casehub.claudony.casehub.fleet.script;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class FleetScriptRunnerTest {

    private final List<String> executionOrder = new ArrayList<>();

    private FleetNodeHandler mockHandler(String type) {
        return new FleetNodeHandler() {
            @Override public String type() { return type; }
            @Override public NodeResult handle(String nodeName, Map<String, Object> spec) {
                executionOrder.add(nodeName);
                return NodeResult.ok(nodeName, type, "provisioned");
            }
        };
    }

    private FleetNodeHandler failingHandler(String type) {
        return new FleetNodeHandler() {
            @Override public String type() { return type; }
            @Override public NodeResult handle(String nodeName, Map<String, Object> spec) {
                executionOrder.add(nodeName);
                return NodeResult.failed(nodeName, type, "boom");
            }
        };
    }

    @Test
    void executeSingleNode() {
        var runner = new FleetScriptRunner(List.of(mockHandler("pool")));
        String yaml = """
                nodes:
                  p:
                    type: pool
                    spec:
                      agentId: test
                """;
        var result = runner.execute(yaml);
        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().getFirst().name()).isEqualTo("p");
        assertThat(executionOrder).containsExactly("p");
    }

    @Test
    void topologicalSort_linearChain() {
        var runner = new FleetScriptRunner(List.of(mockHandler("pool"), mockHandler("channel")));
        String yaml = """
                nodes:
                  c:
                    type: channel
                    dependsOn: [b]
                    spec: {}
                  a:
                    type: pool
                    spec: {}
                  b:
                    type: pool
                    dependsOn: [a]
                    spec: {}
                """;
        var result = runner.execute(yaml);
        assertThat(result.allSucceeded()).isTrue();
        assertThat(executionOrder).containsExactly("a", "b", "c");
    }

    @Test
    void topologicalSort_independentNodes() {
        var runner = new FleetScriptRunner(List.of(mockHandler("pool")));
        String yaml = """
                nodes:
                  x:
                    type: pool
                    spec: {}
                  y:
                    type: pool
                    spec: {}
                  z:
                    type: pool
                    spec: {}
                """;
        var result = runner.execute(yaml);
        assertThat(result.allSucceeded()).isTrue();
        assertThat(executionOrder).hasSize(3);
    }

    @Test
    void cycleDetection() {
        var runner = new FleetScriptRunner(List.of(mockHandler("pool")));
        String yaml = """
                nodes:
                  a:
                    type: pool
                    dependsOn: [b]
                    spec: {}
                  b:
                    type: pool
                    dependsOn: [a]
                    spec: {}
                """;
        assertThatThrownBy(() -> runner.execute(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void dependencyFailureCascades() {
        var runner = new FleetScriptRunner(List.of(failingHandler("pool"), mockHandler("channel")));
        String yaml = """
                nodes:
                  pool-a:
                    type: pool
                    spec: {}
                  ch:
                    type: channel
                    dependsOn: [pool-a]
                    spec: {}
                """;
        var result = runner.execute(yaml);
        assertThat(result.allSucceeded()).isFalse();
        assertThat(result.results()).hasSize(2);
        assertThat(result.results().get(0).success()).isFalse();
        assertThat(result.results().get(1).success()).isFalse();
        assertThat(result.results().get(1).message()).contains("dependency");
        assertThat(executionOrder).containsExactly("pool-a");
    }

    @Test
    void independentNodesNotAffectedBySiblingFailure() {
        var runner = new FleetScriptRunner(List.of(failingHandler("pool"), mockHandler("channel")));
        String yaml = """
                nodes:
                  pool-bad:
                    type: pool
                    spec: {}
                  ch-good:
                    type: channel
                    spec: {}
                """;
        var result = runner.execute(yaml);
        assertThat(result.allSucceeded()).isFalse();
        var chResult = result.results().stream()
                .filter(r -> r.name().equals("ch-good")).findFirst().orElseThrow();
        assertThat(chResult.success()).isTrue();
    }

    @Test
    void unknownTypeSkipped() {
        var runner = new FleetScriptRunner(List.of(mockHandler("pool")));
        String yaml = """
                nodes:
                  mystery:
                    type: agent
                    spec: {}
                """;
        var result = runner.execute(yaml);
        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.results().getFirst().message()).contains("skipped");
    }

    @Test
    void emptyNodesThrows() {
        var runner = new FleetScriptRunner(List.of());
        assertThatThrownBy(() -> runner.execute("nodes: {}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
