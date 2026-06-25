package io.casehub.claudony.casehub;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ClaudonyProviderConfig(
        Optional<String> command,
        Optional<String> model,
        Optional<String> appendSystemPrompt,
        Optional<String> systemPrompt,
        Optional<String> effort,
        Optional<String> permissionMode,
        Optional<List<String>> tools,
        Optional<List<String>> allowedTools,
        Optional<List<String>> disallowedTools,
        Optional<List<String>> addDirs,
        Optional<String> workingDir) {

    public static final ClaudonyProviderConfig EMPTY = new ClaudonyProviderConfig(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());

    @SuppressWarnings("unchecked")
    public static ClaudonyProviderConfig fromMap(Map<String, Object> map) {
        if (map.isEmpty()) return EMPTY;
        return new ClaudonyProviderConfig(
                optString(map, "command"),
                optString(map, "model"),
                optString(map, "appendSystemPrompt"),
                optString(map, "systemPrompt"),
                optString(map, "effort"),
                optString(map, "permissionMode"),
                optList(map, "tools"),
                optList(map, "allowedTools"),
                optList(map, "disallowedTools"),
                optList(map, "addDirs"),
                optString(map, "workingDir"));
    }

    public static ClaudonyProviderConfig fromConfigMapping(CaseHubConfig.AgentProviderConfig cfg) {
        return new ClaudonyProviderConfig(
                cfg.command(), cfg.model(), cfg.appendSystemPrompt(), cfg.systemPrompt(),
                cfg.effort(), cfg.permissionMode(), cfg.tools(), cfg.allowedTools(),
                cfg.disallowedTools(), cfg.addDirs(), cfg.workingDir());
    }

    private static Optional<String> optString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? Optional.of(s) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Optional<List<String>> optList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof List<?> list ? Optional.of((List<String>) list) : Optional.empty();
    }
}
