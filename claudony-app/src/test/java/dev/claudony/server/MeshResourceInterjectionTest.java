package dev.claudony.server;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
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

    @Test
    void postMessage_eventType_isValid() {
        // EVENT was added to VALID_HUMAN_TYPES — verify it is accepted (no deontic obligation)
        given()
            .contentType(JSON)
            .body("{\"content\":\"tool call: read_file AuthService.java\",\"type\":\"event\"}")
        .when()
            .post("/api/mesh/channels/{name}/messages", channelName)
        .then()
            .statusCode(200)
            .body("messageType", equalTo("EVENT"));
    }

    @Test
    void timeline_afterCursor_returnsOnlyNewerMessages() {
        // Post two messages, capture the first message's id, then fetch timeline with ?after
        var firstId = given()
            .contentType(JSON)
            .body("{\"content\":\"first message\",\"type\":\"status\"}")
        .when()
            .post("/api/mesh/channels/{name}/messages", channelName)
        .then()
            .statusCode(200)
            .extract().path("messageId");

        given()
            .contentType(JSON)
            .body("{\"content\":\"second message\",\"type\":\"status\"}")
        .when()
            .post("/api/mesh/channels/{name}/messages", channelName)
        .then()
            .statusCode(200);

        // ?after=firstId should return only the second message
        given()
        .when()
            .get("/api/mesh/channels/{name}/timeline?after={id}", channelName, firstId)
        .then()
            .statusCode(200)
            .body("$.size()", equalTo(1))
            .body("[0].content", equalTo("second message"));
    }

    @Test
    void timeline_afterCursorAtEnd_returnsEmpty() {
        var lastId = given()
            .contentType(JSON)
            .body("{\"content\":\"only message\",\"type\":\"status\"}")
        .when()
            .post("/api/mesh/channels/{name}/messages", channelName)
        .then()
            .statusCode(200)
            .extract().path("messageId");

        given()
        .when()
            .get("/api/mesh/channels/{name}/timeline?after={id}", channelName, lastId)
        .then()
            .statusCode(200)
            .body("$.size()", equalTo(0));
    }
}
