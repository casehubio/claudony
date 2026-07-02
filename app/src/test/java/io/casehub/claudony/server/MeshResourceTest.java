package io.casehub.claudony.server;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.message.Message;
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
        String channelName = "feed-tagged-" + System.nanoTime();
        Channel ch = channelStore.put(Channel.builder(channelName)
                .semantic(ChannelSemantic.APPEND)
                .build());

        Message msg = Message.builder()
                .channelId(ch.id())
                .sender("agent:test")
                .messageType(MessageType.STATUS)
                .content("hello from channel")
                .build();
        messageStore.put(msg);

        given().when().get("/api/mesh/feed")
            .then()
            .statusCode(200)
            .body("$", hasSize(greaterThan(0)))
            .body("[0].channel", equalTo(ch.name()));
    }

    @Test
    void meshFeed_multiChannel_returnsMergedEntries() {
        String ch1Name = "feed-multi-a-" + System.nanoTime();
        Channel ch1 = channelStore.put(Channel.builder(ch1Name)
                .semantic(ChannelSemantic.APPEND)
                .build());

        String ch2Name = "feed-multi-b-" + System.nanoTime();
        Channel ch2 = channelStore.put(Channel.builder(ch2Name)
                .semantic(ChannelSemantic.APPEND)
                .build());

        Message m1 = Message.builder()
                .channelId(ch1.id())
                .sender("a")
                .messageType(MessageType.STATUS)
                .content("from ch1")
                .build();
        messageStore.put(m1);

        Message m2 = Message.builder()
                .channelId(ch2.id())
                .sender("b")
                .messageType(MessageType.STATUS)
                .content("from ch2")
                .build();
        messageStore.put(m2);

        given().when().get("/api/mesh/feed")
            .then()
            .statusCode(200)
            .body("channel", containsInAnyOrder(ch1.name(), ch2.name()));
    }

    @Test
    void meshFeed_limitTruncates() {
        String channelName = "feed-limit-" + System.nanoTime();
        Channel ch = channelStore.put(Channel.builder(channelName)
                .semantic(ChannelSemantic.APPEND)
                .build());

        for (int i = 0; i < 10; i++) {
            Message msg = Message.builder()
                    .channelId(ch.id())
                    .sender("agent:test")
                    .messageType(MessageType.STATUS)
                    .content("msg-" + i)
                    .build();
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
        String channelName = "sse-test-" + System.nanoTime();
        Channel ch = channelStore.put(Channel.builder(channelName)
                .semantic(ChannelSemantic.APPEND)
                .build());

        int port = io.restassured.RestAssured.port;
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
            new java.net.URL("http://localhost:" + port +
                "/api/mesh/channels/" + ch.name() + "/events").openConnection();
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
        String channelName = "feed-newest-" + System.nanoTime();
        Channel ch = channelStore.put(Channel.builder(channelName)
                .semantic(ChannelSemantic.APPEND)
                .build());

        for (int i = 0; i < 10; i++) {
            Message msg = Message.builder()
                    .channelId(ch.id())
                    .sender("agent:test")
                    .messageType(MessageType.STATUS)
                    .content("msg-" + i)
                    .build();
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
        String busyName = "feed-busy-" + System.nanoTime();
        Channel busy = channelStore.put(Channel.builder(busyName)
                .semantic(ChannelSemantic.APPEND)
                .build());

        String quietName = "feed-quiet-" + System.nanoTime();
        Channel quiet = channelStore.put(Channel.builder(quietName)
                .semantic(ChannelSemantic.APPEND)
                .build());

        // Insert 1 message in quiet channel first (lower ID)
        Message quietMsg = Message.builder()
                .channelId(quiet.id())
                .sender("agent:q")
                .messageType(MessageType.STATUS)
                .content("quiet-msg")
                .build();
        messageStore.put(quietMsg);

        // Insert 5 messages in busy channel (higher IDs)
        for (int i = 0; i < 5; i++) {
            Message msg = Message.builder()
                    .channelId(busy.id())
                    .sender("agent:b")
                    .messageType(MessageType.STATUS)
                    .content("busy-" + i)
                    .build();
            messageStore.put(msg);
        }

        // With limit=100, both channels should appear
        given().when().get("/api/mesh/feed?limit=100")
            .then()
            .statusCode(200)
            .body("channel", hasItems(busy.name(), quiet.name()));
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
