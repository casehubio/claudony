package io.casehub.claudony.server.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class ClaudonyPoolApiTest {

    @Test
    void listPools() {
        RestAssured.given()
                .when().get("/api/claudony/pools")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].name", notNullValue());
    }

    @Test
    void getPool() {
        RestAssured.given()
                .when().get("/api/claudony/pools/default")
                .then().statusCode(200)
                .body("name", is("default"))
                .body("status.min", notNullValue())
                .body("status.max", notNullValue());
    }

    @Test
    void getPoolNotFound() {
        RestAssured.given()
                .when().get("/api/claudony/pools/nonexistent")
                .then().statusCode(404);
    }

    @Test
    void listSessions() {
        RestAssured.given()
                .when().get("/api/claudony/pools/default/sessions")
                .then().statusCode(200)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    void updatePoolScalingTargetTracking() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"scalingType\": \"target-tracking\", \"targetFillRatio\": 0.85, \"cooldown\": \"30s\"}")
                .when().post("/api/claudony/pools/default/update")
                .then().statusCode(200)
                .body("name", is("default"));
    }

    @Test
    void updatePoolScalingNone() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"scalingType\": \"none\"}")
                .when().post("/api/claudony/pools/default/update")
                .then().statusCode(200)
                .body("name", is("default"));
    }

    @Test
    void updatePoolCapacity() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"maxActive\": 8}")
                .when().post("/api/claudony/pools/default/update")
                .then().statusCode(200)
                .body("status.max", is(8));
    }

    @Test
    void updatePoolNotFound() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"scalingType\": \"none\"}")
                .when().post("/api/claudony/pools/nonexistent/update")
                .then().statusCode(404);
    }
}
