package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.BeforeEach;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPoolYamlParserTest {

    private AgentPoolYamlParser parser;

    @BeforeEach
    void setUp() {
        parser = new AgentPoolYamlParser();
    }

    @Test
    void fullYamlProducesDefinition() {
        var yaml = """
                agent-pools:
                  code-reviewer:
                    working-dir: /workspace/reviews
                    policy: SHARED_READ
                    command: claude --model opus
                    pool:
                      min-active: 2
                      max-active: 10
                      eviction: memory-weighted
                """;

        var defs = parser.parse(yaml);
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

    @Test
    void minimalYamlUsesDefaults() {
        var yaml = """
                agent-pools:
                  researcher: {}
                """;

        var defs = parser.parse(yaml);
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

    @Test
    void multiplePoolDefinitions() {
        var yaml = """
                agent-pools:
                  reviewer:
                    working-dir: /reviews
                    pool:
                      max-active: 3
                  coder:
                    command: claude --model sonnet
                    pool:
                      min-active: 1
                      max-active: 8
                """;

        var defs = parser.parse(yaml);
        assertThat(defs).hasSize(2);
        assertThat(defs).extracting(d -> d.agent().name())
                .containsExactlyInAnyOrder("reviewer", "coder");
    }

    @Test
    void lruEvictionStrategy() {
        var yaml = """
                agent-pools:
                  worker:
                    pool:
                      eviction: lru
                """;

        var def = parser.parse(yaml).getFirst();
        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.LRU);
    }

    @Test
    void poolSectionOptional() {
        var yaml = """
                agent-pools:
                  simple-agent:
                    working-dir: /work
                    command: claude
                """;

        var def = parser.parse(yaml).getFirst();
        assertThat(def.agent().name()).isEqualTo("simple-agent");
        assertThat(def.agent().workingDir()).isEqualTo("/work");
        assertThat(def.pool().minActive()).isZero();
        assertThat(def.pool().maxActive()).isEqualTo(10);
    }

    @Test
    void emptyYamlProducesEmptyList() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("---")).isEmpty();
    }

    @Test
    void noAgentPoolsKeyProducesEmptyList() {
        var yaml = """
                other-config:
                  key: value
                """;
        assertThat(parser.parse(yaml)).isEmpty();
    }

    @Test
    void parseIntoRegistersAll() {
        var yaml = """
                agent-pools:
                  alpha:
                    pool:
                      max-active: 5
                  beta: {}
                """;

        var registry = new AgentPoolDefinitionRegistry(new InMemoryRegistryService(event -> {}));
        parser.parseInto(yaml, registry);

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.get("alpha")).isPresent();
        assertThat(registry.get("beta")).isPresent();
    }

    @Test
    void yamlProducesSameResultAsBuilder() {
        var yaml = """
                agent-pools:
                  code-reviewer:
                    working-dir: /workspace/reviews
                    policy: SHARED_READ
                    command: claude --model opus
                    pool:
                      min-active: 2
                      max-active: 10
                      eviction: memory-weighted
                """;

        var fromYaml = parser.parse(yaml).getFirst();

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

        assertThat(fromYaml.agent()).isEqualTo(fromBuilder.agent());
        assertThat(fromYaml.pool()).isEqualTo(fromBuilder.pool());
    }

    @Test
    void caseInsensitivePolicy() {
        var yaml = """
                agent-pools:
                  worker:
                    policy: shared_read
                """;

        var def = parser.parse(yaml).getFirst();
        assertThat(def.agent().policy()).isEqualTo(WorkingDirPolicy.SHARED_READ);
    }

    @Test
    void caseInsensitiveEviction() {
        var yaml = """
                agent-pools:
                  worker:
                    pool:
                      eviction: MEMORY-WEIGHTED
                """;

        var def = parser.parse(yaml).getFirst();
        assertThat(def.pool().eviction()).isEqualTo(EvictionStrategy.MEMORY_WEIGHTED);
    }

    @Test
    void invalidPolicyThrowsWithMessage() {
        var yaml = """
                   agent-pools:
                     worker:
                       policy: INVALID_POLICY
                   """;

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policy")
                .hasMessageContaining("INVALID_POLICY");
    }

    @Test
    void invalidEvictionThrowsWithMessage() {
        var yaml = """
                   agent-pools:
                     worker:
                       pool:
                         eviction: INVALID_EVICTION
                   """;

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eviction")
                .hasMessageContaining("INVALID_EVICTION");
    }

    @Test
    void wrongTypeForMinActiveThrowsWithMessage() {
        var yaml = """
                   agent-pools:
                     worker:
                       pool:
                         min-active: not-a-number
                   """;

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-active");
    }

    @Test
    void targetTrackingScaling() {
        var yaml = """
                agent-pools:
                  reviewer:
                    pool:
                      max-active: 10
                      scaling:
                        type: target-tracking
                        target: 0.7
                        cooldown: 60s
                        scale-in-cooldown: 300s
                """;

        var def = parser.parse(yaml).getFirst();
        var scaling = def.pool().scaling();
        assertThat(scaling).isInstanceOf(ScalingConfig.TargetTrackingConfig.class);
        var tt = (ScalingConfig.TargetTrackingConfig) scaling;
        assertThat(tt.targetFillRatio()).isEqualTo(0.7);
        assertThat(tt.cooldown()).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(tt.scaleInCooldown()).isEqualTo(java.time.Duration.ofSeconds(300));
    }

    @Test
    void stepScaling() {
        var yaml = """
                agent-pools:
                  reviewer:
                    pool:
                      scaling:
                        type: step
                        cooldown: 30s
                        steps:
                          - threshold: 0.8
                            adjustment: 2
                          - threshold: 0.3
                            adjustment: -1
                """;

        var def = parser.parse(yaml).getFirst();
        var scaling = def.pool().scaling();
        assertThat(scaling).isInstanceOf(ScalingConfig.StepConfig.class);
        var step = (ScalingConfig.StepConfig) scaling;
        assertThat(step.steps()).hasSize(2);
        assertThat(step.cooldown()).isEqualTo(java.time.Duration.ofSeconds(30));
    }

    @Test
    void noScalingSectionDefaultsToNoOp() {
        var yaml = """
                agent-pools:
                  reviewer:
                    pool:
                      max-active: 5
                """;

        var def = parser.parse(yaml).getFirst();
        assertThat(def.pool().scaling()).isInstanceOf(ScalingConfig.NoScalingConfig.class);
    }

    @Test
    void customScalingType() {
        var yaml = """
                   agent-pools:
                     reviewer:
                       pool:
                         scaling:
                           type: queue-aware
                           cooldown: 45s
                   """;

        var def     = parser.parse(yaml).getFirst();
        var scaling = def.pool().scaling();
        assertThat(scaling).isInstanceOf(ScalingConfig.CustomScalingConfig.class);
        var custom = (ScalingConfig.CustomScalingConfig) scaling;
        assertThat(custom.beanName()).isEqualTo("queue-aware");
        assertThat(custom.cooldown()).isEqualTo(java.time.Duration.ofSeconds(45));
    }

    @Test
    void scalingCooldownDefaultsTo60s() {
        var yaml = """
                agent-pools:
                  reviewer:
                    pool:
                      scaling:
                        type: target-tracking
                        target: 0.7
                """;

        var def = parser.parse(yaml).getFirst();
        var scaling = (ScalingConfig.TargetTrackingConfig) def.pool().scaling();
        assertThat(scaling.cooldown()).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(scaling.scaleInCooldown()).isEqualTo(java.time.Duration.ofSeconds(60));
    }

    @Test
    void parsesDemandPressureScalingType() {
        var yaml = """
                   agent-pools:
                     reviewer:
                       command: claude
                       pool:
                         max-active: 10
                         scaling:
                           type: demand-pressure
                           exhaustion-threshold: 2
                           latency-threshold-ms: 500
                           cooldown: 60s
                           scale-in-cooldown: 300s
                   """;
        var defs    = parser.parse(yaml);
        var scaling = defs.get(0).pool().scaling();
        assertThat(scaling).isInstanceOf(ScalingConfig.DemandPressureConfig.class);
        var dp = (ScalingConfig.DemandPressureConfig) scaling;
        assertThat(dp.exhaustionThreshold()).isEqualTo(2);
        assertThat(dp.latencyThresholdMs()).isEqualTo(500);
        assertThat(dp.cooldown()).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(dp.scaleInCooldown()).isEqualTo(java.time.Duration.ofSeconds(300));
    }

    @Test
    void demandPressureMissingLatencyThresholdThrows() {
        var yaml = """
                   agent-pools:
                     reviewer:
                       command: claude
                       pool:
                         max-active: 10
                         scaling:
                           type: demand-pressure
                           exhaustion-threshold: 2
                   """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latency-threshold-ms");
    }

    @Test
    void parseBudgetSection() {
        var yaml = """
                   agent-pools:
                     reviewer:
                       pool:
                         budget:
                           cost-limit: 50.0
                           token-limit: 10000000
                           window: 24h
                           enforcement: suspend
                           report-interval: turn
                           no-report-timeout: 10m
                   """;
        var defs   = parser.parse(yaml);
        var budget = defs.get(0).pool().budget();
        assertThat(budget).isNotNull();
        assertThat(budget.costLimit()).isEqualTo(50.0);
        assertThat(budget.tokenLimit()).isEqualTo(10_000_000L);
        assertThat(budget.window()).isEqualTo(java.time.Duration.ofHours(24));
        assertThat(budget.enforcement()).isEqualTo(EnforcementPolicy.SUSPEND);
        assertThat(budget.reportInterval()).isInstanceOf(ReportInterval.Turn.class);
        assertThat(budget.noReportTimeout()).isEqualTo(java.time.Duration.ofMinutes(10));
    }

    @Test
    void budgetDefaultsMerging() {
        var yaml = """
                   budget-defaults:
                     cost-limit: 100.0
                     window: 24h
                     enforcement: block-new
                     report-interval: turn
                     no-report-timeout: 10m
                   agent-pools:
                     reviewer:
                       pool:
                         budget:
                           cost-limit: 50.0
                           enforcement: suspend
                   """;
        var defs   = parser.parse(yaml);
        var budget = defs.get(0).pool().budget();
        assertThat(budget).isNotNull();
        assertThat(budget.costLimit()).isEqualTo(50.0);
        assertThat(budget.window()).isEqualTo(java.time.Duration.ofHours(24));
        assertThat(budget.enforcement()).isEqualTo(EnforcementPolicy.SUSPEND);
    }

    @Test
    void noBudgetReturnsNull() {
        var yaml = """
                   agent-pools:
                     reviewer:
                       pool:
                         min-active: 1
                   """;
        var defs = parser.parse(yaml);
        assertThat(defs.get(0).pool().budget()).isNull();
    }

    @Test
    void periodicReportInterval() {
        var yaml = """
                   agent-pools:
                     reviewer:
                       pool:
                         budget:
                           cost-limit: 50.0
                           window: 1h
                           enforcement: alert
                           report-interval: periodic(5)
                           no-report-timeout: 10m
                   """;
        var defs     = parser.parse(yaml);
        var interval = defs.get(0).pool().budget().reportInterval();
        assertThat(interval).isInstanceOf(ReportInterval.Periodic.class);
        assertThat(((ReportInterval.Periodic) interval).turns()).isEqualTo(5);
    }

    @Test
    void completionReportInterval() {
        var yaml = """
                   agent-pools:
                     reviewer:
                       pool:
                         budget:
                           cost-limit: 50.0
                           window: 1h
                           enforcement: alert
                           report-interval: completion
                           no-report-timeout: 10m
                   """;
        var defs = parser.parse(yaml);
        assertThat(defs.get(0).pool().budget().reportInterval()).isInstanceOf(ReportInterval.Completion.class);
    }

    @Test
    void budgetDefaultsOnlyAppliedWhenBudgetPresent() {
        var yaml = """
                   budget-defaults:
                     cost-limit: 100.0
                     window: 24h
                     enforcement: block-new
                     report-interval: turn
                     no-report-timeout: 10m
                   agent-pools:
                     reviewer:
                       pool:
                         min-active: 1
                   """;
        var defs = parser.parse(yaml);
        assertThat(defs.get(0).pool().budget()).isNull();
    }
}
