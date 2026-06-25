package io.casehub.claudony.casehub;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class WorkerCommandBuilderTest {

    @Test
    void emptyConfig_returnsBaseCommandUnchanged() {
        assertThat(WorkerCommandBuilder.build("claude", ClaudonyProviderConfig.EMPTY))
                .isEqualTo("claude");
    }

    @Test
    void singleFlag_model() {
        var config = configWith(b -> b.model = Optional.of("opus"));
        assertThat(WorkerCommandBuilder.build("claude", config))
                .isEqualTo("claude --model 'opus'");
    }

    @Test
    void systemPrompt_withSpacesAndQuotes() {
        var config = configWith(b -> b.appendSystemPrompt = Optional.of("You're a reviewer"));
        assertThat(WorkerCommandBuilder.build("claude", config))
                .isEqualTo("claude --append-system-prompt 'You'\\''re a reviewer'");
    }

    @Test
    void tools_commaJoinedAndQuoted() {
        var config = configWith(b -> b.tools = Optional.of(List.of("Read", "Bash", "Edit")));
        assertThat(WorkerCommandBuilder.build("claude", config))
                .isEqualTo("claude --tools 'Read,Bash,Edit'");
    }

    @Test
    void toolPattern_shellMetacharactersSurvive() {
        var config = configWith(b -> b.allowedTools = Optional.of(List.of("Read", "Bash(git *)", "Edit")));
        assertThat(WorkerCommandBuilder.build("claude", config))
                .isEqualTo("claude --allowedTools 'Read,Bash(git *),Edit'");
    }

    @Test
    void addDirs_repeatedPerEntry() {
        var config = configWith(b -> b.addDirs = Optional.of(List.of("/tmp/docs", "/tmp/data")));
        assertThat(WorkerCommandBuilder.build("claude", config))
                .isEqualTo("claude --add-dir '/tmp/docs' --add-dir '/tmp/data'");
    }

    @Test
    void systemPromptWinsOverAppend_builderPolicy() {
        var config = new ClaudonyProviderConfig(
                Optional.empty(), Optional.empty(),
                Optional.of("Append this"), Optional.of("Replace entirely"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        String cmd = WorkerCommandBuilder.build("claude", config);
        assertThat(cmd).contains("--system-prompt");
        assertThat(cmd).doesNotContain("--append-system-prompt");
    }

    @Test
    void allFlagsPopulated() {
        var config = new ClaudonyProviderConfig(
                Optional.of("claude-code"), Optional.of("opus"),
                Optional.of("Append prompt"), Optional.empty(),
                Optional.of("high"), Optional.of("auto"),
                Optional.of(List.of("Read", "Bash")), Optional.of(List.of("Edit")),
                Optional.of(List.of("Write")),
                Optional.of(List.of("/dir1")), Optional.of("/workspace"));
        String cmd = WorkerCommandBuilder.build("claude", config);
        assertThat(cmd).startsWith("claude ");
        assertThat(cmd).contains("--model 'opus'");
        assertThat(cmd).contains("--append-system-prompt 'Append prompt'");
        assertThat(cmd).contains("--effort 'high'");
        assertThat(cmd).contains("--permission-mode 'auto'");
        assertThat(cmd).contains("--tools 'Read,Bash'");
        assertThat(cmd).contains("--allowedTools 'Edit'");
        assertThat(cmd).contains("--disallowedTools 'Write'");
        assertThat(cmd).contains("--add-dir '/dir1'");
        // workingDir is NOT a CLI flag — resolved by caller
        assertThat(cmd).doesNotContain("/workspace");
    }

    @Test
    void baseCommandWithExistingFlags_preserved() {
        var config = configWith(b -> b.model = Optional.of("opus"));
        assertThat(WorkerCommandBuilder.build("claude --mcp http://localhost:7778/mcp", config))
                .isEqualTo("claude --mcp http://localhost:7778/mcp --model 'opus'");
    }

    private static ClaudonyProviderConfig configWith(java.util.function.Consumer<ConfigBuilder> customizer) {
        var b = new ConfigBuilder();
        customizer.accept(b);
        return new ClaudonyProviderConfig(
                b.command, b.model, b.appendSystemPrompt, b.systemPrompt,
                b.effort, b.permissionMode, b.tools, b.allowedTools,
                b.disallowedTools, b.addDirs, b.workingDir);
    }

    private static class ConfigBuilder {
        Optional<String> command = Optional.empty();
        Optional<String> model = Optional.empty();
        Optional<String> appendSystemPrompt = Optional.empty();
        Optional<String> systemPrompt = Optional.empty();
        Optional<String> effort = Optional.empty();
        Optional<String> permissionMode = Optional.empty();
        Optional<List<String>> tools = Optional.empty();
        Optional<List<String>> allowedTools = Optional.empty();
        Optional<List<String>> disallowedTools = Optional.empty();
        Optional<List<String>> addDirs = Optional.empty();
        Optional<String> workingDir = Optional.empty();
    }
}
