package io.casehub.claudony.server.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CredentialRoleAugmentorTest {

    @Test
    @TestSecurity(user = "admin", roles = "admin")
    void adminCanPatchCapacity() {
        RestAssured.given()
            .contentType("application/json")
            .body("{\"maxActive\": 12}")
            .when().patch("/api/pools/default/capacity")
            .then()
            .statusCode(200);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "user")
    void nonAdminCannotPatchCapacity() {
        RestAssured.given()
            .contentType("application/json")
            .body("{\"maxActive\": 12}")
            .when().patch("/api/pools/default/capacity")
            .then()
            .statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = "admin")
    void adminCanSuspendSession() {
        RestAssured.given()
            .when().post("/api/pools/default/sessions/nonexistent/suspend")
            .then()
            .statusCode(204);
    }

    @Test
    @TestSecurity(user = "admin", roles = "admin")
    void adminCanUpdateScaling_noDefinition_returns404() {
        RestAssured.given()
            .contentType("application/json")
            .body("{\"type\": \"none\"}")
            .when().patch("/api/pools/default/scaling")
            .then()
            .statusCode(404);
    }
}
