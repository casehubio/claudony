package io.casehub.claudony.casehub;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "claudony.casehub")
public interface CaseHubConfig {

    @WithDefault("false")
    boolean enabled();

    @WithName("channel-layout")
    @WithDefault("normative")
    String channelLayout();

    @WithName("mesh-participation")
    @WithDefault("active")
    String meshParticipation();

    @WithName("worker-exit-poll-ms")
    @WithDefault("5000")
    long workerExitPollMs();

    @WithName("worker-exit-max-poll-failures")
    @WithDefault("3")
    int workerExitMaxPollFailures();

    Workers workers();

    interface Workers {
        @WithName("default-command")
        @WithDefault("claude")
        String defaultCommand();

        @WithName("default-working-dir")
        @WithDefault("${user.home}/claudony-workspace")
        String defaultWorkingDir();

        @WithName("provider-config")
        Map<String, AgentProviderConfig> providerConfig();
    }

    interface AgentProviderConfig {
        Optional<String> command();
        Optional<String> model();
        @WithName("append-system-prompt")
        Optional<String> appendSystemPrompt();
        @WithName("system-prompt")
        Optional<String> systemPrompt();
        Optional<String> effort();
        @WithName("permission-mode")
        Optional<String> permissionMode();
        Optional<List<String>> tools();
        @WithName("allowed-tools")
        Optional<List<String>> allowedTools();
        @WithName("disallowed-tools")
        Optional<List<String>> disallowedTools();
        @WithName("add-dirs")
        Optional<List<String>> addDirs();
        @WithName("working-dir")
        Optional<String> workingDir();
    }
}
