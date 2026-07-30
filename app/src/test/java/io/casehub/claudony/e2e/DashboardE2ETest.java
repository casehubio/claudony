package io.casehub.claudony.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.RequestOptions;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for the Claudony fleet home (/app/).
 *
 * <p>Each test gets a fresh BrowserContext (no state bleed) via PlaywrightBase.
 * All page requests include the test API key via PlaywrightBase.setExtraHTTPHeaders,
 * including fetch() calls made by LitElement components.
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
        var fleetPanel = page.locator("claudony-fleet-panel");
        fleetPanel.waitFor(new Locator.WaitForOptions().setTimeout(10000));
        assertThat(fleetPanel.isVisible()).isTrue();
        page.locator("claudony-fleet-panel .peer-empty").waitFor();
        assertThat(page.locator("claudony-fleet-panel .peer-empty").textContent()).contains("No peers configured");
    }

    @Test
    void sessionGrid_showsEmptyState_whenNoSessions() {
        page.navigate(BASE_URL + "/app/");
        page.locator("claudony-session-grid .empty").waitFor(
                new Locator.WaitForOptions().setTimeout(10000));
        assertThat(page.locator("claudony-session-grid .empty").textContent()).contains("No active sessions");
    }

    @Test
    void newSessionDialog_opensAndCloses() {
        page.navigate(BASE_URL + "/app/");
        page.locator("claudony-session-grid pages-button[label='+ New Session']").click();
        var dialog = page.locator("claudony-session-grid pages-modal");
        assertThat(dialog.isVisible()).isTrue();
        assertThat(page.locator("claudony-session-grid pages-input").first().isVisible()).isTrue();
        page.locator("claudony-session-grid pages-button[label='Cancel']").click();
        page.waitForTimeout(500);
    }

    @Test
    void addPeerDialog_opensAndCloses() {
        page.navigate(BASE_URL + "/app/");
        page.locator("claudony-fleet-panel pages-button[label='+ Add Peer']").click();
        var dialog = page.locator("claudony-fleet-panel pages-modal");
        assertThat(dialog.isVisible()).isTrue();
        assertThat(page.locator("claudony-fleet-panel pages-input").first().isVisible()).isTrue();
        page.locator("claudony-fleet-panel pages-button[label='Cancel']").click();
        page.waitForTimeout(500);
    }

    @Test
    void sessionCard_appearsAfterApiCreate() {
        // Create session via REST API using the page's request context (inherits API key)
        var response = page.request().post(BASE_URL + "/api/sessions",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setHeader("X-Api-Key", API_KEY)
                        .setData("{\"name\":\"playwright-test\"}"));
        assertThat(response.status()).isEqualTo(201);
        try {
            createdSessionId = new ObjectMapper()
                    .readTree(response.text())
                    .get("id").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse session creation response", e);
        }

        // Navigate and wait for card (session-grid polls every 5s — allow 10s)
        page.navigate(BASE_URL + "/app/");
        page.locator("claudony-session-grid .card").waitFor(
                new Locator.WaitForOptions().setTimeout(10000));

        // Server prepends "claudony-" to the name; displayName() strips it back to "playwright-test"
        assertThat(page.locator("claudony-session-grid .card-name").first().textContent())
                .isEqualTo("playwright-test");
        // Status badge present — pages-badge with label attribute
        assertThat(page.locator("claudony-session-grid pages-badge").first().getAttribute("label")).isNotBlank();
    }

    @Test
    void unauthenticated_redirectsToLogin() {
        try (var unauthContext = browser.newContext();
             var unauthPage = unauthContext.newPage()) {
            unauthPage.navigate(BASE_URL + "/app/");
            var redirectedToLogin = unauthPage.url().contains("/auth/login");
            var authOverlayShown = unauthPage.locator("claudony-session-grid pages-modal[variant='alertdialog']").count() > 0;
            assertThat(redirectedToLogin || authOverlayShown)
                    .withFailMessage("Expected auth redirect or overlay, URL was: " + unauthPage.url())
                    .isTrue();
        }
    }
}
