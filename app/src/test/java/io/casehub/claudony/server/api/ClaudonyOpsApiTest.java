package io.casehub.claudony.server.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class ClaudonyOpsApiTest {

    @Test
    void snapshotReturnsOpsSnapshot() {
        RestAssured.given()
                .when().get("/api/ops/snapshot")
                .then().statusCode(200)
                .body("health", notNullValue())
                .body("pools", instanceOf(java.util.List.class))
                .body("peers", instanceOf(java.util.List.class))
                .body("apps", instanceOf(java.util.List.class))
                .body("mesh", notNullValue())
                .body("topology", instanceOf(java.util.List.class))
                .body("timestamp", notNullValue());
    }

    @Test
    void healthReturnsFleetHealth() {
        RestAssured.given()
                .when().get("/api/ops/health")
                .then().statusCode(200)
                .body("totalEntities", greaterThanOrEqualTo(0))
                .body("healthy", greaterThanOrEqualTo(0))
                .body("degraded", greaterThanOrEqualTo(0))
                .body("down", greaterThanOrEqualTo(0))
                .body("orphaned", greaterThanOrEqualTo(0));
    }

    @Test
    void snapshotIncludesPoolData() {
        RestAssured.given()
                .when().get("/api/ops/snapshot")
                .then().statusCode(200)
                .body("pools.size()", greaterThanOrEqualTo(1))
                .body("pools[0].name", notNullValue())
                .body("pools[0].fillRatio", notNullValue());
    }
}
