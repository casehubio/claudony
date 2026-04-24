package dev.claudony.server;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class MeshResourceTest {

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
