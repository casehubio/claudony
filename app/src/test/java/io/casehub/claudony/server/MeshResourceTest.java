package io.casehub.claudony.server;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.persistence.memory.InMemoryChannelStore;
import io.casehub.qhorus.persistence.memory.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class MeshResourceTest {

    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    @AfterEach
    void cleanup() {
        messageStore.clear();
        channelStore.clear();
    }

    @Test
    void meshConfig_returnsStrategyAndInterval() {
        given().when().get("/api/mesh/config")
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("strategy", equalTo("poll"))
            .body("interval", equalTo(3000));
    }

    @Test
    void meshConfig_returnsCursorStalenessMinutes() {
        given().when().get("/api/mesh/config")
            .then()
            .statusCode(200)
            .body("cursorStalenessMinutes", equalTo(30));
    }

    @Test
    void meshConfig_returnsActorId() {
        given().when().get("/api/mesh/config")
               .then()
               .statusCode(200)
               .body("actorId", equalTo("test"));
    }

    @Test
    void meshInstances_returnsEmptyList() {
        given().when().get("/api/mesh/instances")
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("$.size()", equalTo(0));
    }

    @Test
    void postMessage_response_withoutInReplyTo_returns400() {
        createTestChannel("val-test-" + System.nanoTime());
        given().contentType("application/json")
               .body("{\"content\":\"reply\",\"type\":\"RESPONSE\",\"correlationId\":\"c1\"}")
               .post("/api/mesh/channels/val-test-" + System.nanoTime() + "/messages")
               .then().statusCode(400);
    }

    @Test
    void postMessage_handoff_withoutTarget_returns400() {
        String name = "val-handoff-" + System.nanoTime();
        createTestChannel(name);
        given().contentType("application/json")
               .body("{\"content\":\"handoff\",\"type\":\"HANDOFF\",\"inReplyTo\":1,\"correlationId\":\"c1\"}")
               .post("/api/mesh/channels/" + name + "/messages")
               .then().statusCode(400);
    }

    @Test
    void postMessage_command_withTarget_succeeds() {
        String name = "val-cmd-" + System.nanoTime();
        createTestChannel(name);
        given().contentType("application/json")
               .body("{\"content\":\"do this\",\"type\":\"COMMAND\",\"target\":\"agent-2\",\"topic\":\"work\"}")
               .post("/api/mesh/channels/" + name + "/messages")
               .then().statusCode(200);
    }

    private Channel createTestChannel(String name) {
        return channelStore.put(Channel.builder(name)
                                       .semantic(ChannelSemantic.APPEND).build());
    }
}

@QuarkusTest
class MeshResourceAuthTest {

    @Test
    void meshConfig_withoutAuth_returns401() {
        given().when().get("/api/mesh/config")
            .then().statusCode(401);
    }

    @Test
    void postMessage_withoutAuth_returns401() {
        given()
            .contentType("application/json")
            .body("{\"content\":\"hello\",\"type\":\"status\"}")
        .when()
            .post("/api/mesh/channels/any/messages")
        .then()
            .statusCode(401);
    }
}
