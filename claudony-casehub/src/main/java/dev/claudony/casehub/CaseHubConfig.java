package dev.claudony.casehub;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.util.Map;

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

    Workers workers();

    interface Workers {
        /** Map of capability name to launch command. Key "default" is the fallback. */
        Map<String, String> commands();

        @WithName("default-working-dir")
        @WithDefault("${user.home}/claudony-workspace")
        String defaultWorkingDir();
    }
}
