package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProactiveYamlParserTest {

    @Test
    void parseProactiveScaling() {
        var yaml = """
                agent-pools:
                  warm-pool:
                    working-dir: /tmp
                    pool:
                      min-active: 0
                      max-active: 10
                      scaling:
                        type: proactive
                        target-active: 3
                        cooldown: 30s
                """;

        var parser = new AgentPoolYamlParser();
        var defs = parser.parse(yaml);

        assertThat(defs).hasSize(1);
        var scaling = defs.get(0).pool().scaling();
        assertThat(scaling).isInstanceOf(ScalingConfig.ProactiveConfig.class);
        var proactive = (ScalingConfig.ProactiveConfig) scaling;
        assertThat(proactive.targetActive()).isEqualTo(3);
        assertThat(proactive.cooldown().getSeconds()).isEqualTo(30);
    }

    @Test
    void parseProactiveScaling_defaultCooldown() {
        var yaml = """
                agent-pools:
                  warm-pool:
                    working-dir: /tmp
                    pool:
                      min-active: 0
                      max-active: 10
                      scaling:
                        type: proactive
                        target-active: 5
                """;

        var parser = new AgentPoolYamlParser();
        var defs = parser.parse(yaml);

        var proactive = (ScalingConfig.ProactiveConfig) defs.get(0).pool().scaling();
        assertThat(proactive.targetActive()).isEqualTo(5);
        assertThat(proactive.cooldown().getSeconds()).isEqualTo(60);
    }
}
