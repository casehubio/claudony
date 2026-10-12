package io.casehub.claudony.casehub.fleet;

public record EidosPoolBridgeConfig(
        int defaultMinActive,
        int defaultMaxActive,
        String defaultCommand
) {
    public static final EidosPoolBridgeConfig DEFAULTS =
            new EidosPoolBridgeConfig(0, 5, "claude");
}
