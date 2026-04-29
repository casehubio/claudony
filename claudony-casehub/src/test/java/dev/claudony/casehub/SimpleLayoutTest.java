package dev.claudony.casehub;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.api.message.MessageType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SimpleLayoutTest {

    private final SimpleLayout layout = new SimpleLayout();

    @Test
    void channelsFor_returnsTwoChannels() {
        List<CaseChannelLayout.ChannelSpec> specs = layout.channelsFor(UUID.randomUUID(), null);
        assertThat(specs).hasSize(2);
    }

    @Test
    void channelsFor_purposes_areWorkAndObserve() {
        List<CaseChannelLayout.ChannelSpec> specs = layout.channelsFor(UUID.randomUUID(), null);
        assertThat(specs).extracting(CaseChannelLayout.ChannelSpec::purpose)
                .containsExactly("work", "observe");
    }

    @Test
    void channelsFor_allUseAppendSemantic() {
        List<CaseChannelLayout.ChannelSpec> specs = layout.channelsFor(UUID.randomUUID(), null);
        assertThat(specs).extracting(CaseChannelLayout.ChannelSpec::semantic)
                .containsOnly(ChannelSemantic.APPEND);
    }

    @Test
    void channelsFor_observeChannel_allowsOnlyEventType() {
        CaseChannelLayout.ChannelSpec observe = layout.channelsFor(UUID.randomUUID(), null).stream()
                .filter(s -> s.purpose().equals("observe"))
                .findFirst().orElseThrow();
        assertThat(observe.allowedTypes()).containsExactly(MessageType.EVENT);
    }

    @Test
    void channelsFor_workChannel_allowsAllTypes() {
        CaseChannelLayout.ChannelSpec work = layout.channelsFor(UUID.randomUUID(), null).stream()
                .filter(s -> s.purpose().equals("work"))
                .findFirst().orElseThrow();
        assertThat(work.allowedTypes()).isNull();
    }

    @Test
    void channelsFor_hasNoOversightChannel() {
        List<CaseChannelLayout.ChannelSpec> specs = layout.channelsFor(UUID.randomUUID(), null);
        assertThat(specs).extracting(CaseChannelLayout.ChannelSpec::purpose)
                .doesNotContain("oversight");
    }
}
