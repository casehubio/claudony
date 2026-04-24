package dev.claudony.server;

import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.qhorus.testing.InMemoryChannelStore;
import io.quarkiverse.qhorus.testing.InMemoryMessageStore;
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

    @Inject QhorusMcpTools tools;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    private String channelName;

    @BeforeEach
    void createChannel() {
        channelName = "test-interjection-" + System.nanoTime();
        tools.createChannel(channelName, "test channel for interjection", "APPEND", null);
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
            .body("sender", equalTo("human"))
            .body("channelName", equalTo(channelName))
            .body("messageType", equalTo("STATUS"))
            .body("messageId", notNullValue());

        // Verify the message was actually persisted (round-trip check)
        given()
        .when()
            .get("/api/mesh/channels/{name}/timeline", channelName)
        .then()
            .statusCode(200)
            .body("[0].sender", equalTo("human"))
            .body("[0].content", equalTo("prioritise security"));
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
}
