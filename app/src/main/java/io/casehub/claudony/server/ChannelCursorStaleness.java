package io.casehub.claudony.server;

import io.casehub.platform.api.preferences.PreferenceKey;
import io.casehub.platform.api.preferences.SingleValuePreference;

/**
 * Preference controlling how long a cached channel cursor remains "fresh".
 * Cursors older than this threshold trigger the stale-reconnect prompt
 * rather than an immediate silent catch-up.
 *
 * <p>Configure via {@code application.properties}:
 * <pre>
 *   casehub.platform.preferences.defaults.claudony.channelCursorStalenessMinutes=30
 * </pre>
 */
public record ChannelCursorStaleness(int minutes) implements SingleValuePreference {

    public static final PreferenceKey<ChannelCursorStaleness> KEY =
            new PreferenceKey<>(
                    "claudony",
                    "channelCursorStalenessMinutes",
                    new ChannelCursorStaleness(30),
                    s -> {
                        int v = Integer.parseInt(s.trim());
                        if (v <= 0) throw new IllegalArgumentException(
                                "channelCursorStalenessMinutes must be positive, got: " + v);
                        return new ChannelCursorStaleness(v);
                    });
}
