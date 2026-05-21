package io.casehub.claudony.server;

import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ChannelBackendBootstrapTest {

    @Inject SessionRegistry      registry;
    @Inject ServerStartup        startup;
    @Inject ChannelGateway       gateway;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    private String caseId;
    private String channelName;
    private UUID   channelUuid;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID().toString();
        channelName = "case-" + caseId + "/work";
        channelUuid = UUID.randomUUID();

        Channel ch = new Channel();
        ch.id = channelUuid;
        ch.name = channelName;
        ch.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch);

        Instant now = Instant.now();
        registry.register(new Session(
                UUID.randomUUID().toString(), "tmux-" + caseId, "~/ws",
                "claude", SessionStatus.IDLE, now, now,
                Optional.empty(), Optional.of(caseId), Optional.empty()));
    }

    @AfterEach
    void tearDown() {
        messageStore.clear();
        channelStore.clear();
        registry.all().stream()
                .filter(s -> s.name().startsWith("tmux-" + caseId))
                .forEach(s -> registry.remove(s.id()));
    }

    @Test
    void bootstrapChannelBackends_registersBackendForCaseChannels() {
        startup.bootstrapChannelBackends();

        var backends = gateway.listBackends(channelUuid);
        assertThat(backends)
                .extracting(ChannelGateway.BackendRegistration::backendId)
                .contains(ClaudonyChannelBackend.BACKEND_ID);
    }

    @Test
    void bootstrapChannelBackends_isIdempotent_noDuplicates() {
        startup.bootstrapChannelBackends();
        startup.bootstrapChannelBackends();

        long count = gateway.listBackends(channelUuid).stream()
                .filter(b -> ClaudonyChannelBackend.BACKEND_ID.equals(b.backendId()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void bootstrapChannelBackends_skipsSessionsWithoutCaseId() {
        // Clear the caseId session and register a standalone session
        registry.all().stream()
                .filter(s -> s.name().startsWith("tmux-" + caseId))
                .forEach(s -> registry.remove(s.id()));
        channelStore.clear();

        Instant now = Instant.now();
        registry.register(new Session(
                UUID.randomUUID().toString(), "standalone", "~/ws",
                "claude", SessionStatus.IDLE, now, now,
                Optional.empty(), Optional.empty(), Optional.empty()));

        startup.bootstrapChannelBackends();

        // No caseId sessions → backend not registered for our test channel UUID
        assertThat(gateway.listBackends(channelUuid)).isEmpty();
    }
}
