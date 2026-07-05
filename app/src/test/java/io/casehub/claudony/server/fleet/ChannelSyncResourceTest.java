package io.casehub.claudony.server.fleet;

import io.casehub.claudony.server.ClaudonyChannelBackend;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.persistence.memory.InMemoryChannelStore;
import io.casehub.qhorus.persistence.memory.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ChannelSyncResourceTest {

    @Inject ChannelGateway gateway;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    @AfterEach
    void cleanup() {
        messageStore.clear();
        channelStore.clear();
    }

    @Test
    void sync_noFleetKey_returns401() {
        given()
            .contentType(JSON)
            .body("{\"channelId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"channelName\":\"case-test/work\"}")
        .when()
            .post("/api/internal/channels/sync")
        .then()
            .statusCode(401);
    }

    @Test
    void sync_validRequest_returns204_andInitialisesChannel() {
        UUID channelId = UUID.randomUUID();
        String channelName = "case-sync-" + channelId + "/work";

        given()
            .header("X-Api-Key", "test-fleet-key-do-not-use-in-prod")
            .contentType(JSON)
            .body("{\"channelId\":\"" + channelId + "\","
                + "\"channelName\":\"" + channelName + "\"}")
        .when()
            .post("/api/internal/channels/sync")
        .then()
            .statusCode(204);

        // ChannelInitialisedEvent fired by initChannel() → observer registers backend
        assertThat(gateway.listBackends(channelId))
                .extracting(ChannelGateway.BackendRegistration::backendId)
                .contains(ClaudonyChannelBackend.BACKEND_ID);
    }
}
