package io.casehub.claudony.casehub.fleet.script;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class FleetScriptParserTest {

    private final FleetScriptParser parser = new FleetScriptParser();

    @Test
    void parsesFullYaml() {
        String yaml = """
                desiredState:
                  namespace: fleet
                  name: test
                variables:
                  ws: /tmp/work
                nodes:
                  my-pool:
                    type: pool
                    spec:
                      agentId: reviewer
                      minActive: 1
                      maxActive: 5
                """;
        var script = parser.parse(yaml);
        assertThat(script.desiredState()).isNotNull();
        assertThat(script.desiredState().namespace()).isEqualTo("fleet");
        assertThat(script.variables()).containsEntry("ws", "/tmp/work");
        assertThat(script.nodes()).containsKey("my-pool");
        assertThat(script.nodes().get("my-pool").type()).isEqualTo("pool");
    }

    @Test
    void parsesMinimalYaml_noDesiredStateNoVariables() {
        String yaml = """
                nodes:
                  ch:
                    type: channel
                    spec:
                      name: team/general
                """;
        var script = parser.parse(yaml);
        assertThat(script.desiredState()).isNull();
        assertThat(script.variables()).isNull();
        assertThat(script.nodes()).containsKey("ch");
    }

    @Test
    void parsesDependsOn() {
        String yaml = """
                nodes:
                  pool-a:
                    type: pool
                    spec:
                      agentId: a
                  ch:
                    type: channel
                    dependsOn: [pool-a]
                    spec:
                      name: test
                """;
        var script = parser.parse(yaml);
        assertThat(script.nodes().get("ch").dependsOn()).containsExactly("pool-a");
        assertThat(script.nodes().get("pool-a").dependsOn()).isEmpty();
    }

    @Test
    void throwsOnEmptyNodes() {
        String yaml = """
                nodes: {}
                """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsOnMalformedYaml() {
        assertThatThrownBy(() -> parser.parse("{not valid yaml: [[["))
                .isInstanceOf(Exception.class);
    }

    @Test
    void substitutesVariablesInStringValues() {
        String yaml = """
                variables:
                  ws: /tmp/work
                  prefix: team/review
                nodes:
                  p:
                    type: pool
                    spec:
                      workingDir: ${var.ws}
                  ch:
                    type: channel
                    spec:
                      name: ${var.prefix}/main
                """;
        var script = parser.parse(yaml);
        var result = parser.substituteVariables(script);
        assertThat(result.nodes().get("p").spec().get("workingDir")).isEqualTo("/tmp/work");
        assertThat(result.nodes().get("ch").spec().get("name")).isEqualTo("team/review/main");
    }

    @Test
    void substitutesInNestedMaps() {
        String yaml = """
                variables:
                  cool: 30s
                nodes:
                  p:
                    type: pool
                    spec:
                      scaling:
                        cooldown: ${var.cool}
                """;
        var script = parser.parse(yaml);
        var result = parser.substituteVariables(script);
        @SuppressWarnings("unchecked")
        var scaling = (Map<String, Object>) result.nodes().get("p").spec().get("scaling");
        assertThat(scaling.get("cooldown")).isEqualTo("30s");
    }

    @Test
    void substitutesInLists() {
        String yaml = """
                variables:
                  t: COMMAND
                nodes:
                  ch:
                    type: channel
                    spec:
                      allowedTypes:
                        - ${var.t}
                        - STATUS
                """;
        var script = parser.parse(yaml);
        var result = parser.substituteVariables(script);
        @SuppressWarnings("unchecked")
        var types = (List<Object>) result.nodes().get("ch").spec().get("allowedTypes");
        assertThat(types).containsExactly("COMMAND", "STATUS");
    }

    @Test
    void throwsOnMissingVariable() {
        String yaml = """
                nodes:
                  p:
                    type: pool
                    spec:
                      dir: ${var.missing}
                """;
        var script = parser.parse(yaml);
        assertThatThrownBy(() -> parser.substituteVariables(script))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void noVariables_noSubstitution() {
        String yaml = """
                nodes:
                  p:
                    type: pool
                    spec:
                      dir: /literal
                """;
        var script = parser.parse(yaml);
        var result = parser.substituteVariables(script);
        assertThat(result.nodes().get("p").spec().get("dir")).isEqualTo("/literal");
    }

    @Test
    void leavesNonStringValuesUntouched() {
        String yaml = """
                variables:
                  x: hello
                nodes:
                  p:
                    type: pool
                    spec:
                      minActive: 2
                      enabled: true
                """;
        var script = parser.parse(yaml);
        var result = parser.substituteVariables(script);
        assertThat(result.nodes().get("p").spec().get("minActive")).isEqualTo(2);
        assertThat(result.nodes().get("p").spec().get("enabled")).isEqualTo(true);
    }
}
