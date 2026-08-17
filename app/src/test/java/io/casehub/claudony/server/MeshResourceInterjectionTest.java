package io.casehub.claudony.server;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.persistence.memory.InMemoryChannelStore;
import io.casehub.qhorus.persistence.memory.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class MeshResourceInterjectionTest {

    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    private String channelName;

    @BeforeEach
    void createChannel() {
        channelName = "test-interjection-" + System.nanoTime();
        channelStore.put(Channel.builder(channelName)
                .description("test channel for interjection")
                .semantic(ChannelSemantic.APPEND)
                .allowedWriters(java.util.List.of())
                .build());
    }

    @AfterEach
    void cleanUp() {
        messageStore.clear();
        channelStore.clear();
    }

    @Test
    void postMessage_sendsToChannel() {
        given()
            .contentType(JSON)
            .body("{\"content\":\"prioritise security\",\"type\":\"status\"}")
        .when()
            .post("/api/mesh/channels/{name}/messages", channelName)
        .then()
            .statusCode(200)
            .body("sender", equalTo("human:test"))
            .body("channelName", equalTo(channelName))
            .body("messageType", equalTo("STATUS"))
            .body("messageId", notNullValue());

        // Verify round-trip via qhorus ChannelResource timeline
        given()
        .when()
            .get("/api/channels/{name}/timeline", channelName)
        .then()
            .statusCode(200)
            .body("[0].sender", equalTo("human:test"))
            .body("[0].content", equalTo("prioritise security"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "user")
    void postMessage_differentUsers_produceDistinctSenders() {
        given()
            .contentType(JSON)
            .body("{\"content\":\"alice's directive\",\"type\":\"command\"}")
        .when()
            .post("/api/mesh/channels/{name}/messages", channelName)
        .then()
            .statusCode(200)
            .body("sender", equalTo("human:alice"));
    }

    @Test
    void postMessage_senderHasHumanPrefix() {
        given()
            .contentType(JSON)
            .body("{\"content\":\"any message\",\"type\":\"status\"}")
        .when()
            .post("/api/mesh/channels/{name}/messages", channelName)
        .then()
            .statusCode(200)
            .body("sender", startsWith("human:"));
    }

    @Test
    void postMessage_blankContent_returns400() {
        given()
            .contentType(JSON)
            .body("{\"content\":\"\",\"type\":\"status\"}")
        .when()
            .post("/api/mesh/channels/{name}/messages", channelName)
        .then()
            .statusCode(400);
    }

    @Test
    void postMessage_invalidType_returns400() {
        given()
            .contentType(JSON)
            .body("{\"content\":\"hello\",\"type\":\"blah\"}")
        .when()
            .post("/api/mesh/channels/{name}/messages", channelName)
        .then()
            .statusCode(400);
    }

    @Test
    void postMessage_unknownChannel_returns404() {
        given()
            .contentType(JSON)
            .body("{\"content\":\"hello\",\"type\":\"status\"}")
        .when()
            .post("/api/mesh/channels/{name}/messages", "does-not-exist-xyz-abc")
        .then()
            .statusCode(404);
    }

    @Test
    void postMessage_eventType_isValid() {
        given()
            .contentType(JSON)
            .body("{\"type\":\"event\"}")
        .when()
            .post("/api/mesh/channels/{name}/messages", channelName)
        .then()
            .statusCode(200)
            .body("messageType", equalTo("EVENT"));
    }

    @Test
    void postMessage_autoJoinsMember() {
        given().contentType(JSON)
               .body("{\"content\": \"hello\", \"type\": \"STATUS\"}")
               .when().post("/api/mesh/channels/{name}/messages", channelName)
               .then().statusCode(200);

        // Verify auto-join via qhorus ChannelResource members endpoint
        given().when().get("/api/channels/{name}/members", channelName)
               .then()
               .statusCode(200)
               .body("$.size()", greaterThan(0));
    }
}
