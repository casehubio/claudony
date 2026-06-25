package io.casehub.claudony.casehub;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class ClaudonyProviderConfigTest {

    @Test
    void empty_allFieldsAbsent() {
        var config = ClaudonyProviderConfig.EMPTY;
        assertThat(config.command()).isEmpty();
        assertThat(config.model()).isEmpty();
        assertThat(config.appendSystemPrompt()).isEmpty();
        assertThat(config.systemPrompt()).isEmpty();
        assertThat(config.effort()).isEmpty();
        assertThat(config.permissionMode()).isEmpty();
        assertThat(config.tools()).isEmpty();
        assertThat(config.allowedTools()).isEmpty();
        assertThat(config.disallowedTools()).isEmpty();
        assertThat(config.addDirs()).isEmpty();
        assertThat(config.workingDir()).isEmpty();
    }

    @Test
    void fromMap_allFieldsPopulated() {
        var map = Map.<String, Object>of(
                "command", "claude",
                "model", "opus",
                "appendSystemPrompt", "You are a reviewer",
                "systemPrompt", "Replace default",
                "effort", "high",
                "permissionMode", "auto",
                "tools", List.of("Read", "Bash"),
                "allowedTools", List.of("Bash(git *)"),
                "disallowedTools", List.of("Write"),
                "addDirs", List.of("/tmp/docs", "/tmp/data"));
        var workingDirMap = new java.util.HashMap<>(map);
        workingDirMap.put("workingDir", "/custom/dir");
        var config = ClaudonyProviderConfig.fromMap(workingDirMap);

        assertThat(config.command()).contains("claude");
        assertThat(config.model()).contains("opus");
        assertThat(config.appendSystemPrompt()).contains("You are a reviewer");
        assertThat(config.systemPrompt()).contains("Replace default");
        assertThat(config.effort()).contains("high");
        assertThat(config.permissionMode()).contains("auto");
        assertThat(config.tools()).contains(List.of("Read", "Bash"));
        assertThat(config.allowedTools()).contains(List.of("Bash(git *)"));
        assertThat(config.disallowedTools()).contains(List.of("Write"));
        assertThat(config.addDirs()).contains(List.of("/tmp/docs", "/tmp/data"));
        assertThat(config.workingDir()).contains("/custom/dir");
    }

    @Test
    void fromMap_emptyMap_returnsEmpty() {
        var config = ClaudonyProviderConfig.fromMap(Map.of());
        assertThat(config).isEqualTo(ClaudonyProviderConfig.EMPTY);
    }

    @Test
    void fromMap_listsAreListOfString() {
        var config = ClaudonyProviderConfig.fromMap(Map.of(
                "tools", List.of("Read", "Bash", "Edit")));
        assertThat(config.tools()).contains(List.of("Read", "Bash", "Edit"));
    }

    @Test
    void fromMap_unknownKeysIgnored() {
        var config = ClaudonyProviderConfig.fromMap(Map.of(
                "model", "opus",
                "unknownKey", "ignored",
                "anotherUnknown", 42));
        assertThat(config.model()).contains("opus");
        assertThat(config.command()).isEmpty();
    }

    @Test
    void bothSystemPrompts_recordHoldsBoth() {
        var config = ClaudonyProviderConfig.fromMap(Map.of(
                "systemPrompt", "Replace",
                "appendSystemPrompt", "Append"));
        assertThat(config.systemPrompt()).contains("Replace");
        assertThat(config.appendSystemPrompt()).contains("Append");
    }

    @Test
    void fromConfigMapping_allFieldsPopulated() {
        var agentConfig = new CaseHubConfig.AgentProviderConfig() {
            @Override public Optional<String> command() { return Optional.of("claude"); }
            @Override public Optional<String> model() { return Optional.of("opus"); }
            @Override public Optional<String> appendSystemPrompt() { return Optional.of("You are a reviewer"); }
            @Override public Optional<String> systemPrompt() { return Optional.of("Replace default"); }
            @Override public Optional<String> effort() { return Optional.of("high"); }
            @Override public Optional<String> permissionMode() { return Optional.of("auto"); }
            @Override public Optional<List<String>> tools() { return Optional.of(List.of("Read", "Bash")); }
            @Override public Optional<List<String>> allowedTools() { return Optional.of(List.of("Bash(git *)")); }
            @Override public Optional<List<String>> disallowedTools() { return Optional.of(List.of("Write")); }
            @Override public Optional<List<String>> addDirs() { return Optional.of(List.of("/tmp/docs", "/tmp/data")); }
            @Override public Optional<String> workingDir() { return Optional.of("/custom/dir"); }
        };

        var config = ClaudonyProviderConfig.fromConfigMapping(agentConfig);

        assertThat(config.command()).contains("claude");
        assertThat(config.model()).contains("opus");
        assertThat(config.appendSystemPrompt()).contains("You are a reviewer");
        assertThat(config.systemPrompt()).contains("Replace default");
        assertThat(config.effort()).contains("high");
        assertThat(config.permissionMode()).contains("auto");
        assertThat(config.tools()).contains(List.of("Read", "Bash"));
        assertThat(config.allowedTools()).contains(List.of("Bash(git *)"));
        assertThat(config.disallowedTools()).contains(List.of("Write"));
        assertThat(config.addDirs()).contains(List.of("/tmp/docs", "/tmp/data"));
        assertThat(config.workingDir()).contains("/custom/dir");
    }

    @Test
    void fromConfigMapping_emptyOptionals_reflectedInConfig() {
        var agentConfig = new CaseHubConfig.AgentProviderConfig() {
            @Override public Optional<String> command() { return Optional.empty(); }
            @Override public Optional<String> model() { return Optional.empty(); }
            @Override public Optional<String> appendSystemPrompt() { return Optional.empty(); }
            @Override public Optional<String> systemPrompt() { return Optional.empty(); }
            @Override public Optional<String> effort() { return Optional.empty(); }
            @Override public Optional<String> permissionMode() { return Optional.empty(); }
            @Override public Optional<List<String>> tools() { return Optional.empty(); }
            @Override public Optional<List<String>> allowedTools() { return Optional.empty(); }
            @Override public Optional<List<String>> disallowedTools() { return Optional.empty(); }
            @Override public Optional<List<String>> addDirs() { return Optional.empty(); }
            @Override public Optional<String> workingDir() { return Optional.empty(); }
        };

        var config = ClaudonyProviderConfig.fromConfigMapping(agentConfig);

        assertThat(config.command()).isEmpty();
        assertThat(config.model()).isEmpty();
        assertThat(config.appendSystemPrompt()).isEmpty();
        assertThat(config.systemPrompt()).isEmpty();
        assertThat(config.effort()).isEmpty();
        assertThat(config.permissionMode()).isEmpty();
        assertThat(config.tools()).isEmpty();
        assertThat(config.allowedTools()).isEmpty();
        assertThat(config.disallowedTools()).isEmpty();
        assertThat(config.addDirs()).isEmpty();
        assertThat(config.workingDir()).isEmpty();
    }
}
