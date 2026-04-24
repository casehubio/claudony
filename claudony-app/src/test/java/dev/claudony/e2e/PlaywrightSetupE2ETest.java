package dev.claudony.e2e;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture verification tests for the Playwright E2E test infrastructure.
 *
 * <p>These tests verify setup, not product features. Run these first on a new machine
 * or CI environment. A failure here means the infrastructure is broken — fix it before
 * running DashboardE2ETest or TerminalPageE2ETest.
 *
 * <p>Tests ordered from simplest (Chromium launches) to most complex (unauthenticated blocked).
 */
@QuarkusTest
class PlaywrightSetupE2ETest extends PlaywrightBase {

    /** Verifies Chromium is installed and can be launched. */
    @Test
    void playwright_chromiumLaunches() {
        assertThat(browser.isConnected()).isTrue();
        assertThat(page).isNotNull();
    }

    /** Verifies the Quarkus test server is reachable on port 8081. */
    @Test
    void playwright_canNavigateToServer() {
        var response = page.navigate(BASE_URL + "/app/");
        assertThat(response.status()).isEqualTo(200);
    }

    /**
     * Verifies page.setExtraHTTPHeaders() injects the API key and the server accepts it.
     * If this test fails, authenticated dashboard requests will silently fail too.
     */
    @Test
    void authHeader_allowsProtectedEndpoint() {
        var response = page.navigate(BASE_URL + "/api/sessions");
        assertThat(response.status()).isEqualTo(200);
    }

    /**
     * Verifies the test context is what provides access — not a misconfigured open server.
     * Creates a fresh context without any extra headers and confirms the server rejects it.
     */
    @Test
    void unauthenticated_context_isBlocked() {
        try (var unauthContext = browser.newContext();
             var unauthPage = unauthContext.newPage()) {
            var response = unauthPage.navigate(BASE_URL + "/api/sessions");
            // Server must reject unauthenticated requests with 401 (API) or 302 (page redirect)
            assertThat(response.status()).isIn(401, 302);
        }
    }
}
