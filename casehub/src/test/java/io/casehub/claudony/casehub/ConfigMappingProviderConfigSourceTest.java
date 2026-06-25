package io.casehub.claudony.casehub;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ConfigMappingProviderConfigSourceTest {

    @Test
    void forAgent_knownId_returnsPopulatedConfig() {
        var agentConfig = stubAgentConfig("opus", Optional.of("high"));
        var source = new ConfigMappingProviderConfigSource(
                stubWorkersConfig(Map.of("code-reviewer", agentConfig)));

        var config = source.forAgent("code-reviewer");
        assertThat(config.model()).contains("opus");
        assertThat(config.effort()).contains("high");
    }

    @Test
    void forAgent_unknownId_returnsEmpty() {
        var source = new ConfigMappingProviderConfigSource(
                stubWorkersConfig(Map.of()));

        assertThat(source.forAgent("unknown")).isEqualTo(ClaudonyProviderConfig.EMPTY);
    }

    @Test
    void forAgent_emptyMap_returnsEmpty() {
        var source = new ConfigMappingProviderConfigSource(
                stubWorkersConfig(Map.of()));

        assertThat(source.forAgent("anything")).isEqualTo(ClaudonyProviderConfig.EMPTY);
    }

    @Test
    void declaredAgentIds_returnsProviderConfigKeys() {
        var source = new ConfigMappingProviderConfigSource(
                stubWorkersConfig(Map.of(
                        "code-reviewer", stubAgentConfig("opus", Optional.empty()),
                        "researcher", stubAgentConfig("sonnet", Optional.empty()))));

        assertThat(source.declaredAgentIds()).containsExactlyInAnyOrder("code-reviewer", "researcher");
    }

    private CaseHubConfig.Workers stubWorkersConfig(Map<String, CaseHubConfig.AgentProviderConfig> providerConfig) {
        return new CaseHubConfig.Workers() {
            @Override public String defaultCommand() { return "claude"; }
            @Override public String defaultWorkingDir() { return "/tmp"; }
            @Override public Map<String, CaseHubConfig.AgentProviderConfig> providerConfig() { return providerConfig; }
        };
    }

    private CaseHubConfig.AgentProviderConfig stubAgentConfig(String model, Optional<String> effort) {
        return new CaseHubConfig.AgentProviderConfig() {
            @Override public Optional<String> command() { return Optional.empty(); }
            @Override public Optional<String> model() { return Optional.of(model); }
            @Override public Optional<String> appendSystemPrompt() { return Optional.empty(); }
            @Override public Optional<String> systemPrompt() { return Optional.empty(); }
            @Override public Optional<String> effort() { return effort; }
            @Override public Optional<String> permissionMode() { return Optional.empty(); }
            @Override public Optional<List<String>> tools() { return Optional.empty(); }
            @Override public Optional<List<String>> allowedTools() { return Optional.empty(); }
            @Override public Optional<List<String>> disallowedTools() { return Optional.empty(); }
            @Override public Optional<List<String>> addDirs() { return Optional.empty(); }
            @Override public Optional<String> workingDir() { return Optional.empty(); }
        };
    }
}
