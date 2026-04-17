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
}
