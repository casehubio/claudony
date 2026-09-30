package io.casehub.claudony.casehub.fleet;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "claudony.iotdb")
public interface IoTDBConfig {
    @WithDefault("false") boolean enabled();
    @WithDefault("localhost") String host();
    @WithDefault("6667") int port();
    @WithDefault("root") String user();
    @WithDefault("root") String password();
}
