package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPoolAnnotationScannerTest {

    private AgentPoolAnnotationScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new AgentPoolAnnotationScanner();
    }

    @PooledAgent(name = "code-reviewer", workingDir = "/workspace/reviews",
                 policy = WorkingDirPolicy.SHARED_READ, command = "claude --model opus")
    @AgentPool(minActive = 2, maxActive = 10, eviction = EvictionStrategy.MEMORY_WEIGHTED)
    static class FullyConfiguredAgent {}

    @Test
    void fullAnnotationProducesDefinition() {
        var defs = scanner.scan(FullyConfiguredAgent.class);
        assertThat(defs).hasSize(1);

        var def = defs.getFirst();
        assertThat(def.agent().name()).isEqualTo("code-reviewer");
        assertThat(def.agent().workingDir()).isEqualTo("/workspace/reviews");
        assertThat(def.agent().policy()).isEqualTo(WorkingDirPolicy.SHARED_READ);
        assertThat(def.agent().command()).isEqualTo("claude --model opus");
        assertThat(def.pool().minActive()).isEqualTo(2);
        assertThat(def.pool().maxActive()).isEqualTo(10);
        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.MEMORY_WEIGHTED);
    }

    @PooledAgent(name = "researcher")
    static class MinimalAgent {}

    @Test
    void minimalAnnotationUsesDefaults() {
        var defs = scanner.scan(MinimalAgent.class);
        assertThat(defs).hasSize(1);

        var def = defs.getFirst();
        assertThat(def.agent().name()).isEqualTo("researcher");
        assertThat(def.agent().workingDir()).isNull();
        assertThat(def.agent().policy()).isEqualTo(WorkingDirPolicy.EXCLUSIVE);
        assertThat(def.agent().command()).isNull();
        assertThat(def.pool().minActive()).isZero();
        assertThat(def.pool().maxActive()).isEqualTo(10);
        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.MEMORY_WEIGHTED);
    }

    @PooledAgent(name = "coder")
    @AgentPool(minActive = 1, maxActive = 5, eviction = EvictionStrategy.LRU)
    static class LruAgent {}

    @Test
    void lruEvictionStrategy() {
        var def = scanner.scan(LruAgent.class).getFirst();
        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.LRU);
    }

    @PooledAgent(name = "reviewer", workingDir = "/reviews")
    @AgentPool(maxActive = 3)
    static class PartialPoolConfig {}

    @Test
    void partialPoolConfigPreservesDefaults() {
        var def = scanner.scan(PartialPoolConfig.class).getFirst();
        assertThat(def.pool().minActive()).isZero();
        assertThat(def.pool().maxActive()).isEqualTo(3);
    }

    static class UnannotatedClass {}

    @Test
    void unannotatedClassSkipped() {
        var defs = scanner.scan(UnannotatedClass.class);
        assertThat(defs).isEmpty();
    }

    @PooledAgent(name = "first")
    static class FirstAgent {}

    @PooledAgent(name = "second")
    static class SecondAgent {}

    @Test
    void multipleClassesScanned() {
        var defs = scanner.scan(FirstAgent.class, UnannotatedClass.class, SecondAgent.class);
        assertThat(defs).hasSize(2);
        assertThat(defs).extracting(d -> d.agent().name())
                .containsExactly("first", "second");
    }

    @Test
    void scanIntoRegistersAll() {
        var registry = new AgentPoolDefinitionRegistry();
        scanner.scanInto(registry, FirstAgent.class, SecondAgent.class);

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.get("first")).isPresent();
        assertThat(registry.get("second")).isPresent();
    }

    @PooledAgent(name = "worker", command = "claude --model sonnet")
    static class CommandOnlyAgent {}

    @Test
    void commandWithoutWorkingDir() {
        var def = scanner.scan(CommandOnlyAgent.class).getFirst();
        assertThat(def.agent().command()).isEqualTo("claude --model sonnet");
        assertThat(def.agent().workingDir()).isNull();
    }

    @Test
    void emptyClassArrayProducesEmptyList() {
        assertThat(scanner.scan()).isEmpty();
    }

    @Test
    void scanProducesDefinitionsEquivalentToBuilder() {
        var fromAnnotation = scanner.scan(FullyConfiguredAgent.class).getFirst();

        var fromBuilder = AgentPoolDefinition.builder()
                .agent("code-reviewer")
                    .workingDir("/workspace/reviews")
                    .policy(WorkingDirPolicy.SHARED_READ)
                    .command("claude --model opus")
                .pool()
                    .minActive(2)
                    .maxActive(10)
                    .eviction(EvictionStrategy.MEMORY_WEIGHTED)
                .build();

        assertThat(fromAnnotation.agent()).isEqualTo(fromBuilder.agent());
        assertThat(fromAnnotation.pool()).isEqualTo(fromBuilder.pool());
    }
}
