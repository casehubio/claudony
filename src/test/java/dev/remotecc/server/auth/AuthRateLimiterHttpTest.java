package dev.remotecc.server.auth;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

/**
 * HTTP-level test that verifies the rate limiter is wired to the correct routes
 * and returns the correct 429 response (status, headers, body).
 *
 * Uses @InjectMock CredentialStore so /auth/register requires a token (isEmpty=false),
 * making every request 403 until the rate limit kicks in (then 429).
 */
@QuarkusTest
class AuthRateLimiterHttpTest {

    @InjectMock
    CredentialStore credentialStore;

    @Inject
    AuthRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        Mockito.when(credentialStore.isEmpty()).thenReturn(false);
        rateLimiter.resetForTest();
    }

    @AfterEach
    void tearDown() {
        // Reset so accumulated attempts don't bleed into other test classes
        // (all @QuarkusTest classes share one app instance and one AuthRateLimiter bean).
        rateLimiter.resetForTest();
    }

    @Test
    void registerIsRateLimitedAfterMaxAttempts() {
        // Requests 1..MAX_ATTEMPTS hit the rate limiter but are not yet blocked;
        // /auth/register returns 403 (invalid token) for each.
        for (int i = 0; i < AuthRateLimiter.MAX_ATTEMPTS; i++) {
            given().when().get("/auth/register?token=bad-token")
                .then().statusCode(403);
        }

        // Request MAX_ATTEMPTS+1: rate limiter intercepts before the endpoint, returns 429.
        given().when().get("/auth/register?token=bad-token")
            .then()
            .statusCode(429)
            .header("Retry-After", String.valueOf(AuthRateLimiter.WINDOW.toSeconds()))
            .contentType(containsString("application/json"))
            .body(containsString("Too many requests"));
    }
}
