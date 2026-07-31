package io.casehub.claudony.server;

import io.casehub.qhorus.api.gateway.BackendRegistration;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.persistence.memory.InMemoryChannelStore;
import io.casehub.qhorus.persistence.memory.InMemoryMessageStore;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ChannelInitialisedObserverTest {

    @Inject
    ChannelGateway       gateway;
    @Inject
    InMemoryChannelStore channelStore;
    @Inject
    InMemoryMessageStore messageStore;

    @AfterEach
    void cleanup() {
        messageStore.clear();
        channelStore.clear();
    }

    @Test
    void channelInitialised_caseChannel_registersBackend() {
        UUID channelId = UUID.randomUUID();
        gateway.initChannel(channelId, new ChannelRef(channelId, "case-" + channelId + "/work"));

        assertThat(gateway.listBackends(channelId))
                .extracting(BackendRegistration::backendId)
                .contains(ClaudonyChannelBackend.BACKEND_ID);
    }

    @Test
    void channelInitialised_nonCaseChannel_registersBackend() {
        UUID channelId = UUID.randomUUID();
        gateway.initChannel(channelId, new ChannelRef(channelId, "team/engineering"));

        assertThat(gateway.listBackends(channelId))
                .extracting(BackendRegistration::backendId)
                .contains(ClaudonyChannelBackend.BACKEND_ID);
    }

    @Test
    void channelInitialised_calledTwice_noDuplicateBackend() {
        UUID   channelId   = UUID.randomUUID();
        String channelName = "case-" + channelId + "/observe";
        gateway.initChannel(channelId, new ChannelRef(channelId, channelName));
        gateway.initChannel(channelId, new ChannelRef(channelId, channelName));

        long count = gateway.listBackends(channelId).stream()
                            .filter(b -> ClaudonyChannelBackend.BACKEND_ID.equals(b.backendId()))
                            .count();
        assertThat(count).isEqualTo(1);
    }
}
