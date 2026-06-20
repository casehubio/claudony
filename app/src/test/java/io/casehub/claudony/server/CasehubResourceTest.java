package io.casehub.claudony.server;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

// TODO #149: 202 happy path (AgentCase registered, startCase() returns UUID) requires
// CasehubEnabledProfile — non-trivial setup. Covered by manual dev mode validation in #149.
@QuarkusTest
class CasehubResourceTest {

    @Test
    void startAgent_unauthenticated_returns401() {
        given()
            .when().post("/api/casehub/cases/agent")
            .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "test", roles = "user")
    void startAgent_engineAbsent_returns503() {
        given()
            .when().post("/api/casehub/cases/agent")
            .then()
            .statusCode(503)
            .body("error", notNullValue());
    }
}
