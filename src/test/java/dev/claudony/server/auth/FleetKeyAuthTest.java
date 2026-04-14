package dev.claudony.server.auth;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class FleetKeyAuthTest {

    // Test profile: claudony.agent.api-key=test-api-key-do-not-use-in-prod
    // Test profile: claudony.fleet-key=test-fleet-key-do-not-use-in-prod

    @Test
    void agentApiKeyStillAccepted() {
        given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .when().get("/api/sessions")
            .then().statusCode(200);
    }

    @Test
    void fleetKeyAccepted() {
        given()
            .header("X-Api-Key", "test-fleet-key-do-not-use-in-prod")
            .when().get("/api/sessions")
            .then().statusCode(200);
    }

    @Test
    void unknownKeyRejected() {
        given()
            .header("X-Api-Key", "not-a-valid-key-at-all")
            .when().get("/api/sessions")
            .then().statusCode(401);
    }
}
