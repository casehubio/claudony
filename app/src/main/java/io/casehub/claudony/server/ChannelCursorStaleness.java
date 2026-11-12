package io.casehub.claudony.server;

import io.casehub.platform.api.preferences.PreferenceKey;
import io.casehub.platform.api.preferences.SingleValuePreference;

public record ChannelCursorStaleness(int minutes) implements SingleValuePreference {

    public static final PreferenceKey<ChannelCursorStaleness> KEY =
            new PreferenceKey<>(
                    "claudony",
                    "channelCursorStalenessMinutes",
                    new ChannelCursorStaleness(30),
                    s -> {
                        int v = Integer.parseInt(s.trim());
                        if (v <= 0) {
                            throw new IllegalArgumentException(
                                    "channelCursorStalenessMinutes must be positive, got: " + v);
                        }
                        return new ChannelCursorStaleness(v);
                    });

    @Override
    public String toSerializedValue() {
        return String.valueOf(minutes);
    }
}
