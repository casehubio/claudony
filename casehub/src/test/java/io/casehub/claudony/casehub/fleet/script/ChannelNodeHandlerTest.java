package io.casehub.claudony.casehub.fleet.script;

import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

class ChannelNodeHandlerTest {

    private final Map<String, ChannelCreateRequest> createdChannels = new ConcurrentHashMap<>();
    private ChannelNodeHandler handler;

    @BeforeEach
    void setUp() {
        createdChannels.clear();
        handler = new ChannelNodeHandler(request -> createdChannels.put(request.name(), request));
    }

    @Test
    void type() {
        assertThat(handler.type()).isEqualTo("channel");
    }

    @Test
    void handlesFullSpec() {
        var spec = Map.<String, Object>of(
                "name", "team/reviews",
                "description", "Code review coordination",
                "semantic", "APPEND",
                "allowedTypes", List.of("COMMAND", "RESPONSE", "STATUS")
        );
        var result = handler.handle("review-channel", spec);
        assertThat(result.success()).isTrue();
        assertThat(createdChannels).containsKey("team/reviews");
        var req = createdChannels.get("team/reviews");
        assertThat(req.semantic()).isEqualTo(ChannelSemantic.APPEND);
        assertThat(req.allowedTypes()).contains(MessageType.COMMAND, MessageType.RESPONSE, MessageType.STATUS);
    }

    @Test
    void handlesMinimalSpec() {
        var spec = Map.<String, Object>of("name", "team/general");
        var result = handler.handle("general-channel", spec);
        assertThat(result.success()).isTrue();
        assertThat(createdChannels).containsKey("team/general");
    }

    @Test
    void failsOnMissingName() {
        var spec = Map.<String, Object>of("description", "no name");
        var result = handler.handle("bad-channel", spec);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("name");
    }
}
