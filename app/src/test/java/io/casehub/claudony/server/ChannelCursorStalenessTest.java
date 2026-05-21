package io.casehub.claudony.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelCursorStalenessTest {

    @Test
    void defaultValue_is30Minutes() {
        assertThat(ChannelCursorStaleness.KEY.defaultValue().minutes()).isEqualTo(30);
    }

    @Test
    void qualifiedName_matchesPreferenceKeyConvention() {
        assertThat(ChannelCursorStaleness.KEY.qualifiedName())
                .isEqualTo("claudony.channelCursorStalenessMinutes");
    }

    @Test
    void parse_convertsStringToInt() {
        assertThat(ChannelCursorStaleness.KEY.parse("60").minutes()).isEqualTo(60);
    }

    @Test
    void parse_trimsWhitespace() {
        assertThat(ChannelCursorStaleness.KEY.parse("  45  ").minutes()).isEqualTo(45);
    }

    @Test
    void parse_rejectsZero() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ChannelCursorStaleness.KEY.parse("0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_rejectsNegative() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ChannelCursorStaleness.KEY.parse("-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
