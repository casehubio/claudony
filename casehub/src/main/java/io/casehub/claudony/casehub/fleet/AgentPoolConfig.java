package io.casehub.claudony.casehub.fleet;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "claudony.agent-pool")
public interface AgentPoolConfig {

    @WithName("min-active")
    @WithDefault("0")
    int minActive();

    @WithName("max-active")
    @WithDefault("10")
    int maxActive();
}
