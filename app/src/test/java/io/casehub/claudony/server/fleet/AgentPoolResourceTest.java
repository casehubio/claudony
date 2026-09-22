package io.casehub.claudony.server.fleet;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class AgentPoolResourceTest {

    @Test
    void poolStatus_returnsValidJson() {
        given()
            .when().get("/api/agent-pools")
            .then()
            .statusCode(200)
            .body("health", is(notNullValue()))
            .body("active", is(notNullValue()))
            .body("total", is(notNullValue()));
    }

    @Test
    void poolStatus_reflectsDefaultConfig() {
        given()
            .when().get("/api/agent-pools")
            .then()
            .statusCode(200)
            .body("min", equalTo(0))
            .body("max", equalTo(10));
    }
}
