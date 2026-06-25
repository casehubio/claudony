package io.casehub.claudony.casehub;

import java.util.List;
import java.util.Optional;

public final class WorkerCommandBuilder {

    private WorkerCommandBuilder() {}

    public static String build(String baseCommand, ClaudonyProviderConfig config) {
        var sb = new StringBuilder(baseCommand);

        appendString(sb, "--model", config.model());
        if (config.systemPrompt().isPresent()) {
            appendString(sb, "--system-prompt", config.systemPrompt());
        } else {
            appendString(sb, "--append-system-prompt", config.appendSystemPrompt());
        }
        appendString(sb, "--effort", config.effort());
        appendString(sb, "--permission-mode", config.permissionMode());
        appendList(sb, "--tools", config.tools());
        appendList(sb, "--allowedTools", config.allowedTools());
        appendList(sb, "--disallowedTools", config.disallowedTools());
        config.addDirs().ifPresent(dirs -> dirs.forEach(dir -> appendString(sb, "--add-dir", Optional.of(dir))));

        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String flag, Optional<String> value) {
        value.ifPresent(v -> sb.append(' ').append(flag).append(' ').append(shellQuote(v)));
    }

    private static void appendList(StringBuilder sb, String flag, Optional<List<String>> value) {
        value.ifPresent(list -> {
            if (!list.isEmpty()) {
                sb.append(' ').append(flag).append(' ').append(shellQuote(String.join(",", list)));
            }
        });
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
