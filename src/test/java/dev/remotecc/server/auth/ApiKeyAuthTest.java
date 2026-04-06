package dev.remotecc.server.auth;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

/**
 * Tests the API key mechanism against a real protected endpoint (/api/sessions).
 * Test API key configured in application.properties: %test.remotecc.agent.api-key=test-api-key-do-not-use-in-prod
 */
@QuarkusTest
class ApiKeyAuthTest {

    @Test
    void noCredentialsReturns401() {
        given().when().get("/api/sessions")
            .then().statusCode(401);
    }

    @Test
    void validApiKeyReturns200() {
        given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .when().get("/api/sessions")
            .then().statusCode(200);
    }

    @Test
    void wrongApiKeyReturns401() {
        given()
            .header("X-Api-Key", "wrong-key")
            .when().get("/api/sessions")
            .then().statusCode(401);
    }

    @Test
    void devCookieIsRejectedOutsideDevMode() {
        // LaunchMode in tests is TEST, not DEVELOPMENT.
        // The dev cookie must not authenticate — the LaunchMode guard must hold.
        given()
            .cookie("remotecc-dev-key", "test-api-key-do-not-use-in-prod")
            .when().get("/api/sessions")
            .then().statusCode(401);
    }
}
