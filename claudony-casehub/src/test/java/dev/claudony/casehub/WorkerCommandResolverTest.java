package dev.claudony.casehub;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class WorkerCommandResolverTest {

    @Test
    void resolve_exactCapabilityMatch_returnsConfiguredCommand() {
        var config = Map.of(
                "code-reviewer", "claude --mcp http://localhost:7778/mcp",
                "researcher", "ollama run llama3",
                "default", "claude");
        var resolver = new WorkerCommandResolver(config);

        assertThat(resolver.resolve(Set.of("code-reviewer")))
                .isEqualTo("claude --mcp http://localhost:7778/mcp");
    }

    @Test
    void resolve_noMatch_returnsDefault() {
        var config = Map.of("default", "claude");
        var resolver = new WorkerCommandResolver(config);

        assertThat(resolver.resolve(Set.of("unknown-capability"))).isEqualTo("claude");
    }

    @Test
    void resolve_multipleCapabilities_firstMatchWins() {
        var config = Map.of(
                "code-reviewer", "claude",
                "researcher", "ollama run llama3",
                "default", "claude");
        var resolver = new WorkerCommandResolver(config);

        String result = resolver.resolve(Set.of("researcher", "code-reviewer"));
        assertThat(result).isIn("claude", "ollama run llama3");
    }

    @Test
    void resolve_emptyCapabilities_returnsDefault() {
        var config = Map.of("default", "claude");
        var resolver = new WorkerCommandResolver(config);

        assertThat(resolver.resolve(Set.of())).isEqualTo("claude");
    }

    @Test
    void getAvailableCapabilities_returnsAllExceptDefault() {
        var config = Map.of(
                "code-reviewer", "claude",
                "researcher", "ollama run llama3",
                "default", "claude");
        var resolver = new WorkerCommandResolver(config);

        Set<String> caps = resolver.getAvailableCapabilities();
        assertThat(caps).containsExactlyInAnyOrder("code-reviewer", "researcher");
        assertThat(caps).doesNotContain("default");
    }

    @Test
    void resolve_noDefaultConfigured_throwsWhenNoMatch() {
        var config = Map.of("code-reviewer", "claude");
        var resolver = new WorkerCommandResolver(config);

        assertThatThrownBy(() -> resolver.resolve(Set.of("unknown")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no command configured");
    }
}
