package io.casehub.claudony.server.fleet;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class PoolResourceTest {

    @Test
    void listPools_returnsDefaultPool() {
        RestAssured.given()
            .when().get("/api/pools")
            .then()
            .statusCode(200)
            .body("$.size()", greaterThanOrEqualTo(1))
            .body("[0].name", notNullValue())
            .body("[0].status.health", equalTo("HEALTHY"))
            .body("[0].scalingType", notNullValue());
    }

    @Test
    void getPool_returnsDetail() {
        RestAssured.given()
            .when().get("/api/pools/default")
            .then()
            .statusCode(200)
            .body("name", equalTo("default"))
            .body("status.min", notNullValue())
            .body("status.max", notNullValue())
            .body("demand", notNullValue());
    }

    @Test
    void getPool_notFound_returns404() {
        RestAssured.given()
            .when().get("/api/pools/nonexistent")
            .then()
            .statusCode(404);
    }

    @Test
    void listSessions_returnsArray() {
        RestAssured.given()
            .when().get("/api/pools/default/sessions")
            .then()
            .statusCode(200)
            .body("$", instanceOf(java.util.List.class));
    }

    @Test
    void listSessions_notFound_returns404() {
        RestAssured.given()
            .when().get("/api/pools/nonexistent/sessions")
            .then()
            .statusCode(404);
    }

    @Test
    void patchCapacity_nonAdmin_returns403() {
        RestAssured.given()
                   .contentType("application/json")
                   .body("{\"maxActive\": 15}")
                   .when().patch("/api/pools/default/capacity")
                   .then()
                   .statusCode(403);
    }

    @Test
    void suspendSession_nonAdmin_returns403() {
        RestAssured.given()
                   .when().post("/api/pools/default/sessions/nonexistent/suspend")
                   .then()
                   .statusCode(403);
    }

    @Test
    void destroySession_nonAdmin_returns403() {
        RestAssured.given()
                   .when().delete("/api/pools/default/sessions/nonexistent")
                   .then()
                   .statusCode(403);
    }

    @Test
    void patchScaling_nonAdmin_returns403() {
        RestAssured.given()
                   .contentType("application/json")
                   .body("{\"type\": \"none\"}")
                   .when().patch("/api/pools/default/scaling")
                   .then()
                   .statusCode(403);
    }
}
