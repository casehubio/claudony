package io.casehub.claudony.casehub;

import io.casehub.claudony.casehub.CaseChannelLayout.ChannelSpec;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class NormativeChannelLayoutTest {

    private final NormativeChannelLayout layout = new NormativeChannelLayout();

    @Test
    void channelsFor_returnsThreeChannels() {
        List<ChannelSpec> specs = layout.channelsFor(UUID.randomUUID(), null);
        assertThat(specs).hasSize(3);
    }

    @Test
    void channelsFor_purposes_areWorkObserveOversight() {
        List<ChannelSpec> specs = layout.channelsFor(UUID.randomUUID(), null);
        assertThat(specs).extracting(CaseChannelLayout.ChannelSpec::purpose)
                .containsExactly("work", "observe", "oversight");
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
    void channelsFor_oversightChannel_allowedTypesIsNull() {
        CaseChannelLayout.ChannelSpec oversight = layout.channelsFor(UUID.randomUUID(), null).stream()
                .filter(s -> s.purpose().equals("oversight"))
                .findFirst().orElseThrow();
        assertThat(oversight.allowedTypes()).isNull();
    }

    @Test
    void channelsFor_oversightChannel_deniedTypesContainsEventOnly() {
        CaseChannelLayout.ChannelSpec oversight = layout.channelsFor(UUID.randomUUID(), null).stream()
                .filter(s -> s.purpose().equals("oversight"))
                .findFirst().orElseThrow();
        assertThat(oversight.deniedTypes()).containsExactly(MessageType.EVENT);
    }

    @Test
    void channelsFor_workChannel_deniedTypesIsNull() {
        CaseChannelLayout.ChannelSpec work = layout.channelsFor(UUID.randomUUID(), null).stream()
                .filter(s -> s.purpose().equals("work"))
                .findFirst().orElseThrow();
        assertThat(work.deniedTypes()).isNull();
    }

    @Test
    void channelsFor_observeChannel_deniedTypesIsNull() {
        CaseChannelLayout.ChannelSpec observe = layout.channelsFor(UUID.randomUUID(), null).stream()
                .filter(s -> s.purpose().equals("observe"))
                .findFirst().orElseThrow();
        // deniedTypes null on observe — EVENT is already the only allowed type; denial is redundant
        assertThat(observe.deniedTypes()).isNull();
    }

    @Test
    void channelsFor_workChannel_allowsAllTypes() {
        CaseChannelLayout.ChannelSpec work = layout.channelsFor(UUID.randomUUID(), null).stream()
                .filter(s -> s.purpose().equals("work"))
                .findFirst().orElseThrow();
        assertThat(work.allowedTypes()).isNull();
    }

    @Test
    void channelsFor_caseIdIsIgnored_returnsConsistentSpecs() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        assertThat(layout.channelsFor(id1, null))
                .usingRecursiveComparison()
                .isEqualTo(layout.channelsFor(id2, null));
    }
}
