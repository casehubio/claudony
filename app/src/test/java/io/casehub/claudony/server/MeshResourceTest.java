package io.casehub.claudony.server;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class MeshResourceTest {

    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;
    @Inject ObjectMapper objectMapper;

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
    void meshChannels_returnsEmptyList() {
        given().when().get("/api/mesh/channels")
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("$", hasSize(0));
    }

    @Test
    void meshInstances_returnsEmptyList() {
        given().when().get("/api/mesh/instances")
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("$", hasSize(0));
    }

    @Test
    void meshTimeline_unknownChannel_returnsEmptyList() {
        given().when().get("/api/mesh/channels/does-not-exist/timeline")
            .then()
            .statusCode(200)
            .body("$", hasSize(0));
    }

    @Test
    void meshFeed_returnsEmptyList() {
        given().when().get("/api/mesh/feed")
            .then()
            .statusCode(200)
            .body("$", hasSize(0));
    }

    @Test
    void meshFeed_withMessages_returnsEntriesTaggedWithChannelName() {
        Channel ch = new Channel();
        ch.name = "feed-tagged-" + System.nanoTime();
        ch.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch);

        Message msg = new Message();
        msg.channelId = ch.id;
        msg.sender = "agent:test";
        msg.messageType = MessageType.STATUS;
        msg.content = "hello from channel";
        messageStore.put(msg);

        given().when().get("/api/mesh/feed")
            .then()
            .statusCode(200)
            .body("$", hasSize(greaterThan(0)))
            .body("[0].channel", equalTo(ch.name));
    }

    @Test
    void meshFeed_multiChannel_returnsMergedEntries() {
        Channel ch1 = new Channel();
        ch1.name = "feed-multi-a-" + System.nanoTime();
        ch1.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch1);

        Channel ch2 = new Channel();
        ch2.name = "feed-multi-b-" + System.nanoTime();
        ch2.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch2);

        Message m1 = new Message();
        m1.channelId = ch1.id;
        m1.sender = "a";
        m1.messageType = MessageType.STATUS;
        m1.content = "from ch1";
        messageStore.put(m1);

        Message m2 = new Message();
        m2.channelId = ch2.id;
        m2.sender = "b";
        m2.messageType = MessageType.STATUS;
        m2.content = "from ch2";
        messageStore.put(m2);

        given().when().get("/api/mesh/feed")
            .then()
            .statusCode(200)
            .body("channel", containsInAnyOrder(ch1.name, ch2.name));
    }

    @Test
    void meshFeed_limitTruncates() {
        Channel ch = new Channel();
        ch.name = "feed-limit-" + System.nanoTime();
        ch.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch);

        for (int i = 0; i < 10; i++) {
            Message msg = new Message();
            msg.channelId = ch.id;
            msg.sender = "agent:test";
            msg.messageType = MessageType.STATUS;
            msg.content = "msg-" + i;
            messageStore.put(msg);
        }

        // Global DESC scan: returns the 3 most recent messages (newest first), limited at store level.
        given().when().get("/api/mesh/feed?limit=3")
            .then()
            .statusCode(200)
            .body("$", hasSize(equalTo(3)));
    }

    @Test
    void meshEvents_returnsEventStreamContentType() throws Exception {
        // SSE streams never close naturally. Use raw HttpURLConnection to read
        // just the response status and Content-Type header without blocking on body.
        // @TestSecurity is applied by Quarkus at the application layer, so raw
        // HTTP requests to the test server get the test identity automatically.
        int port = io.restassured.RestAssured.port;
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
            new java.net.URL("http://localhost:" + port + "/api/mesh/events").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(500); // short read timeout — we only need headers
        try {
            int status = conn.getResponseCode(); // reads status line + headers
            String contentType = conn.getHeaderField("Content-Type");
            org.assertj.core.api.Assertions.assertThat(status).isEqualTo(200);
            org.assertj.core.api.Assertions.assertThat(contentType).contains("text/event-stream");
        } catch (java.net.SocketTimeoutException e) {
            // If we get here, the server accepted the connection but didn't respond
            // within 500ms — unexpected, re-throw
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    @Test
    void meshEvents_sseFrameIsValidJson() throws Exception {
        int port = io.restassured.RestAssured.port;
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
            new java.net.URL("http://localhost:" + port + "/api/mesh/events").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000); // SSE tick interval is 3000ms in test config; wait up to 5s
        try {
            int status = conn.getResponseCode();
            org.assertj.core.api.Assertions.assertThat(status).isEqualTo(200);
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            String line;
            // Skip blank lines; first tick fires at t=0 per Multi.createFrom().ticks().every()
            while ((line = reader.readLine()) != null && !line.startsWith("data:")) {}
            org.assertj.core.api.Assertions.assertThat(line).isNotNull().startsWith("data:");
            // RESTEasy SSE may emit "data: " (with space) or "data:" (without); strip the prefix
            String json = line.startsWith("data: ") ? line.substring("data: ".length())
                                                     : line.substring("data:".length());
            JsonNode node = objectMapper.readTree(json);
            org.assertj.core.api.Assertions.assertThat(node.has("channels")).isTrue();
            org.assertj.core.api.Assertions.assertThat(node.has("instances")).isTrue();
            org.assertj.core.api.Assertions.assertThat(node.has("feed")).isTrue();
        } catch (java.net.SocketTimeoutException e) {
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    @Test
    void meshEvents_sseFrameContainsEventId() throws Exception {
        int port = io.restassured.RestAssured.port;
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
            new java.net.URL("http://localhost:" + port + "/api/mesh/events").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try {
            conn.getResponseCode();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null && !line.startsWith("data:")) {}
            String json = line.startsWith("data: ") ? line.substring("data: ".length())
                                                     : line.substring("data:".length());
            JsonNode node = objectMapper.readTree(json);
            org.assertj.core.api.Assertions.assertThat(node.has("_eventId")).isTrue();
            org.assertj.core.api.Assertions.assertThat(node.get("_eventId").isNumber()).isTrue();
        } catch (java.net.SocketTimeoutException e) {
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    @Test
    void meshEvents_withAfterZero_stillDeliversFrame() throws Exception {
        int port = io.restassured.RestAssured.port;
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
            new java.net.URL("http://localhost:" + port + "/api/mesh/events?after=0").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try {
            int status = conn.getResponseCode();
            org.assertj.core.api.Assertions.assertThat(status).isEqualTo(200);
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null && !line.startsWith("data:")) {}
            org.assertj.core.api.Assertions.assertThat(line).isNotNull().startsWith("data:");
        } catch (java.net.SocketTimeoutException e) {
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    @Test
    void channelEvents_unknownChannel_returns404() throws Exception {
        int port = io.restassured.RestAssured.port;
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
            new java.net.URL("http://localhost:" + port + "/api/mesh/channels/does-not-exist/events")
                .openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(500);
        try {
            int status = conn.getResponseCode();
            org.assertj.core.api.Assertions.assertThat(status).isEqualTo(404);
        } catch (java.net.SocketTimeoutException e) {
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    @Test
    void channelEvents_returnsEventStreamContentType() throws Exception {
        Channel ch = new Channel();
        ch.name = "sse-test-" + System.nanoTime();
        ch.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch);

        int port = io.restassured.RestAssured.port;
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
            new java.net.URL("http://localhost:" + port +
                "/api/mesh/channels/" + ch.name + "/events").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(500);
        try {
            int status = conn.getResponseCode();
            String contentType = conn.getHeaderField("Content-Type");
            org.assertj.core.api.Assertions.assertThat(status).isEqualTo(200);
            org.assertj.core.api.Assertions.assertThat(contentType).contains("text/event-stream");
        } catch (java.net.SocketTimeoutException e) {
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    @Test
    void meshFeed_returnsNewestMessages_oldestFallOff() {
        Channel ch = new Channel();
        ch.name = "feed-newest-" + System.nanoTime();
        ch.semantic = ChannelSemantic.APPEND;
        channelStore.put(ch);

        for (int i = 0; i < 10; i++) {
            Message msg = new Message();
            msg.channelId = ch.id;
            msg.sender = "agent:test";
            msg.messageType = MessageType.STATUS;
            msg.content = "msg-" + i;
            messageStore.put(msg);
        }

        // With limit=3, only the 3 newest messages should appear (msg-7, msg-8, msg-9)
        var body = given().when().get("/api/mesh/feed?limit=3")
            .then()
            .statusCode(200)
            .body("$", hasSize(3))
            .extract().body().asString();

        // Newest first: msg-9 should be first
        org.assertj.core.api.Assertions.assertThat(body).contains("msg-9");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("msg-0");
    }

    @Test
    void meshFeed_noChannelStarvation_allChannelsRepresented() {
        Channel busy = new Channel();
        busy.name = "feed-busy-" + System.nanoTime();
        busy.semantic = ChannelSemantic.APPEND;
        channelStore.put(busy);

        Channel quiet = new Channel();
        quiet.name = "feed-quiet-" + System.nanoTime();
        quiet.semantic = ChannelSemantic.APPEND;
        channelStore.put(quiet);

        // Insert 1 message in quiet channel first (lower ID)
        Message quietMsg = new Message();
        quietMsg.channelId = quiet.id;
        quietMsg.sender = "agent:q";
        quietMsg.messageType = MessageType.STATUS;
        quietMsg.content = "quiet-msg";
        messageStore.put(quietMsg);

        // Insert 5 messages in busy channel (higher IDs)
        for (int i = 0; i < 5; i++) {
            Message msg = new Message();
            msg.channelId = busy.id;
            msg.sender = "agent:b";
            msg.messageType = MessageType.STATUS;
            msg.content = "busy-" + i;
            messageStore.put(msg);
        }

        // With limit=100, both channels should appear
        given().when().get("/api/mesh/feed?limit=100")
            .then()
            .statusCode(200)
            .body("channel", hasItems(busy.name, quiet.name));
    }
}

@QuarkusTest
class MeshResourceAuthTest {

    @Test
    void meshChannels_withoutAuth_returns401() {
        given().when().get("/api/mesh/channels")
            .then().statusCode(401);
    }

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
