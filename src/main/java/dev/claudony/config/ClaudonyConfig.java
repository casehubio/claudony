package dev.claudony.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.util.Optional;

@ConfigMapping(prefix = "claudony")
public interface ClaudonyConfig {

    @WithDefault("server")
    String mode();

    @WithDefault("7777")
    int port();

    @WithDefault("localhost")
    String bind();

    @WithName("server.url")
    @WithDefault("http://localhost:7777")
    String serverUrl();

    @WithDefault("claude")
    String claudeCommand();

    @WithDefault("claudony-")
    String tmuxPrefix();

    @WithDefault("auto")
    String terminal();

    @WithName("agent.api-key")
    Optional<String> agentApiKey();

    @WithName("default-working-dir")
    @WithDefault("${user.home}/claudony-workspace")
    String defaultWorkingDir();

    @WithName("credentials-file")
    @WithDefault("${user.home}/.claudony/credentials.json")
    String credentialsFile();

    default boolean isServerMode() { return "server".equals(mode()); }
    default boolean isAgentMode()  { return "agent".equals(mode()); }
}
