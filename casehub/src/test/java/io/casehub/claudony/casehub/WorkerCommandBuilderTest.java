package io.casehub.claudony.casehub;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class WorkerCommandBuilderTest {

    @Test
    void emptyConfig_returnsBaseCommandUnchanged() {
        assertThat(WorkerCommandBuilder.build("claude", ClaudonyProviderConfig.EMPTY, Optional.empty()))
                .isEqualTo("claude");
    }

    @Test
    void singleFlag_model() {
        var config = configWith(b -> b.model = Optional.of("opus"));
        assertThat(WorkerCommandBuilder.build("claude", config, Optional.empty()))
                .isEqualTo("claude --model 'opus'");
    }

    @Test
    void systemPrompt_withSpacesAndQuotes() {
        var config = configWith(b -> b.appendSystemPrompt = Optional.of("You're a reviewer"));
        assertThat(WorkerCommandBuilder.build("claude", config, Optional.empty()))
                .isEqualTo("claude --append-system-prompt 'You'\\''re a reviewer'");
    }

    @Test
    void tools_commaJoinedAndQuoted() {
        var config = configWith(b -> b.tools = Optional.of(List.of("Read", "Bash", "Edit")));
        assertThat(WorkerCommandBuilder.build("claude", config, Optional.empty()))
                .isEqualTo("claude --tools 'Read,Bash,Edit'");
    }

    @Test
    void toolPattern_shellMetacharactersSurvives() {
        var config = configWith(b -> b.allowedTools = Optional.of(List.of("Read", "Bash(git *)", "Edit")));
        assertThat(WorkerCommandBuilder.build("claude", config, Optional.empty()))
                .isEqualTo("claude --allowedTools 'Read,Bash(git *),Edit'");
    }

    @Test
    void addDirs_repeatedPerEntry() {
        var config = configWith(b -> b.addDirs = Optional.of(List.of("/tmp/docs", "/tmp/data")));
        assertThat(WorkerCommandBuilder.build("claude", config, Optional.empty()))
                .isEqualTo("claude --add-dir '/tmp/docs' --add-dir '/tmp/data'");
    }

    @Test
    void systemPromptAndAppendSystemPrompt_bothEmitted() {
        var config = new ClaudonyProviderConfig(
                Optional.empty(), Optional.empty(),
                Optional.of("Append this"), Optional.of("Replace entirely"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        String cmd = WorkerCommandBuilder.build("claude", config, Optional.empty());
        assertThat(cmd).contains("--system-prompt 'Replace entirely'");
        assertThat(cmd).contains("--append-system-prompt 'Append this'");
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
        String cmd = WorkerCommandBuilder.build("claude", config, Optional.empty());
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
        assertThat(WorkerCommandBuilder.build("claude --mcp http://localhost:7778/mcp", config, Optional.empty()))
                .isEqualTo("claude --mcp http://localhost:7778/mcp --model 'opus'");
    }

    @Test
    void dynamicPromptOnly_emitsAppendSystemPrompt() {
        String cmd = WorkerCommandBuilder.build("claude", ClaudonyProviderConfig.EMPTY,
                Optional.of("You are on case XYZ"));
        assertThat(cmd).isEqualTo("claude --append-system-prompt 'You are on case XYZ'");
    }

    @Test
    void dynamicAndStaticAppend_mergedStaticFirst() {
        var config = configWith(b -> b.appendSystemPrompt = Optional.of("Always write tests"));
        String cmd = WorkerCommandBuilder.build("claude", config,
                Optional.of("Mesh channels: work, observe"));
        assertThat(cmd).contains("--append-system-prompt 'Always write tests\n\nMesh channels: work, observe'");
    }

    @Test
    void systemPromptAndDynamic_bothEmitted() {
        var config = configWith(b -> b.systemPrompt = Optional.of("Custom persona"));
        String cmd = WorkerCommandBuilder.build("claude", config,
                Optional.of("Mesh context"));
        assertThat(cmd).contains("--system-prompt 'Custom persona'");
        assertThat(cmd).contains("--append-system-prompt 'Mesh context'");
    }

    @Test
    void allThreePromptSources_allEmitted() {
        var config = new ClaudonyProviderConfig(
                Optional.empty(), Optional.empty(),
                Optional.of("Operator append"), Optional.of("Custom persona"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        String cmd = WorkerCommandBuilder.build("claude", config,
                Optional.of("Mesh context"));
        assertThat(cmd).contains("--system-prompt 'Custom persona'");
        assertThat(cmd).contains("--append-system-prompt 'Operator append\n\nMesh context'");
    }

    @Test
    void emptyDynamicPrompt_noExtraFlag() {
        String cmd = WorkerCommandBuilder.build("claude", ClaudonyProviderConfig.EMPTY, Optional.empty());
        assertThat(cmd).isEqualTo("claude");
    }

    @Test
    void multiLineMeshPrompt_shellQuotingSurvives() {
        String mesh = "ROLE: agent\n\nMESH CHANNELS:\n  work: case-123/work\n\n"
                + "STARTUP:\n  1. register(worker-abc, Starting)\n";
        String cmd = WorkerCommandBuilder.build("claude", ClaudonyProviderConfig.EMPTY,
                Optional.of(mesh));
        assertThat(cmd).contains("--append-system-prompt");
        // Shell quoting wraps the entire value in single quotes
        assertThat(cmd).contains("'ROLE: agent");
        assertThat(cmd).contains("register(worker-abc");
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
