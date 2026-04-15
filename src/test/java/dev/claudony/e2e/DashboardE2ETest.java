package dev.claudony.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.RequestOptions;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for the Claudony dashboard (/app/).
 *
 * <p>Each test gets a fresh BrowserContext (no state bleed) via PlaywrightBase.
 * All page requests include the test API key via PlaywrightBase.setExtraHTTPHeaders,
 * including fetch() calls made by dashboard.js.
 */
@QuarkusTest
class DashboardE2ETest extends PlaywrightBase {

    private String createdSessionId;

    @AfterEach
    void cleanupSession() {
        if (createdSessionId != null) {
            page.request().delete(BASE_URL + "/api/sessions/" + createdSessionId,
                    RequestOptions.create().setHeader("X-Api-Key", API_KEY));
            createdSessionId = null;
        }
    }

    @Test
    void pageTitle_isClaudony() {
        page.navigate(BASE_URL + "/app/");
        assertThat(page.title()).isEqualTo("Claudony");
    }

    @Test
    void fleetPanel_visible_withNoPeersMessage() {
        page.navigate(BASE_URL + "/app/");
        var fleetPanel = page.locator("#fleet-panel");
        assertThat(fleetPanel.isVisible()).isTrue();
        assertThat(page.locator(".peer-empty").textContent()).contains("No peers configured");
    }

    @Test
    void sessionGrid_showsEmptyState_whenNoSessions() {
        page.navigate(BASE_URL + "/app/");
        // Wait for the dashboard to finish its first poll (up to 6s — polls every 5s)
        page.locator(".empty-state").waitFor(
                new Locator.WaitForOptions().setTimeout(6000));
        assertThat(page.locator(".empty-state").textContent()).contains("No active sessions");
    }

    @Test
    void newSessionDialog_opensAndCloses() {
        page.navigate(BASE_URL + "/app/");
        page.locator("#new-session-btn").click();
        var dialog = page.locator("#new-session-dialog");
        assertThat(dialog.isVisible()).isTrue();
        assertThat(page.locator("#new-session-form input[name='name']").isVisible()).isTrue();
        page.locator("#cancel-btn").click();
        assertThat(dialog.isVisible()).isFalse();
    }

    @Test
    void addPeerDialog_opensAndCloses() {
        page.navigate(BASE_URL + "/app/");
        page.locator("#add-peer-btn").click();
        var dialog = page.locator("#add-peer-dialog");
        assertThat(dialog.isVisible()).isTrue();
        assertThat(page.locator("#add-peer-form input[name='url']").isVisible()).isTrue();
        assertThat(page.locator("#add-peer-form input[name='name']").isVisible()).isTrue();
        assertThat(page.locator("#add-peer-form select[name='terminalMode']").isVisible()).isTrue();
        page.locator("#cancel-peer-btn").click();
        assertThat(dialog.isVisible()).isFalse();
    }

    @Test
    void sessionCard_appearsAfterApiCreate() {
        // Create session via REST API using the page's request context (inherits API key)
        var response = page.request().post(BASE_URL + "/api/sessions",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setHeader("X-Api-Key", API_KEY)
                        .setData("{\"name\":\"playwright-test-session\"}"));
        assertThat(response.status()).isEqualTo(201);
        try {
            createdSessionId = new ObjectMapper()
                    .readTree(response.text())
                    .get("id").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse session creation response", e);
        }

        // Navigate to dashboard and wait for card (dashboard polls every 5s — allow 10s)
        page.navigate(BASE_URL + "/app/");
        page.locator(".session-card").waitFor(
                new Locator.WaitForOptions().setTimeout(10000));

        // Card renders with the correct session name (prefix stripped by displayName())
        assertThat(page.locator(".card-name").first().textContent())
                .isEqualTo("playwright-test-session");
        // Status badge is present and non-blank
        assertThat(page.locator(".badge").first().textContent().trim()).isNotBlank();
    }

    @Test
    void unauthenticated_redirectsToLogin() {
        try (var unauthContext = browser.newContext();
             var unauthPage = unauthContext.newPage()) {
            unauthPage.navigate(BASE_URL + "/app/");
            var redirectedToLogin = unauthPage.url().contains("/auth/login");
            var authOverlayShown = unauthPage.locator("#auth-overlay").count() > 0;
            assertThat(redirectedToLogin || authOverlayShown)
                    .withFailMessage("Expected auth redirect or overlay, URL was: " + unauthPage.url())
                    .isTrue();
        }
    }
}
