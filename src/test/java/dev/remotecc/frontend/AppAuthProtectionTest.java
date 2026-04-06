package dev.remotecc.frontend;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

/**
 * Verifies that /app/* is protected — unauthenticated requests must not
 * receive a 200. If application.properties ever loses the /app/* protected
 * path entry, this test catches it.
 */
@QuarkusTest
class AppAuthProtectionTest {

    @Test
    void unauthenticatedDashboardIsRedirectedToLogin() {
        given().redirects().follow(false)
            .when().get("/app/index.html")
            .then().statusCode(allOf(greaterThanOrEqualTo(300), lessThan(400)));
    }

    @Test
    void unauthenticatedSessionPageIsRedirectedToLogin() {
        given().redirects().follow(false)
            .when().get("/app/session.html")
            .then().statusCode(allOf(greaterThanOrEqualTo(300), lessThan(400)));
    }
}
