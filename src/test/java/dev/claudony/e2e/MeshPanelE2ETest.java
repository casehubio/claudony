package dev.claudony.e2e;

import com.microsoft.playwright.Locator;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Playwright E2E tests for the Mesh observation panel.
 *
 * Tests panel structure, collapse/expand behaviour, view switching, and empty
 * state rendering. Does NOT test live message content (requires real Qhorus
 * agent activity — deferred).
 *
 * Run with: mvn test -Pe2e -Dtest=MeshPanelE2ETest
 */
@QuarkusTest
class MeshPanelE2ETest extends PlaywrightBase {

    @Test
    void meshPanel_visibleOnDashboard() {
        page.navigate(BASE_URL + "/app/");

        var panel = page.locator("#mesh-panel");
        assertThat(panel.isVisible()).isTrue();

        var title = page.locator(".mesh-title");
        assertThat(title.isVisible()).isTrue();
        assertThat(title.textContent()).isEqualTo("MESH");

        assertThat(page.locator(".mesh-view-btn").count()).isEqualTo(3);
        assertThat(page.locator(".mesh-view-btn[data-view='overview']").isVisible()).isTrue();
        assertThat(page.locator(".mesh-view-btn[data-view='channel']").isVisible()).isTrue();
        assertThat(page.locator(".mesh-view-btn[data-view='feed']").isVisible()).isTrue();
    }

    @Test
    void meshPanel_collapseAndExpand() {
        page.navigate(BASE_URL + "/app/");

        // Clear localStorage to ensure panel starts expanded
        page.evaluate("() => { localStorage.removeItem('mesh-collapsed'); }");
        page.reload();

        var panel = page.locator("#mesh-panel");
        var expandBtn = page.locator("#mesh-expand-btn");
        var collapseBtn = page.locator("#mesh-collapse-btn");

        // Panel is visible initially
        assertThat(panel.isVisible()).isTrue();

        // Click collapse
        collapseBtn.click();
        assertThat(panel.evaluate("el => el.classList.contains('collapsed')")).isEqualTo(true);
        assertThat(expandBtn.isVisible()).isTrue();

        // Click expand
        expandBtn.click();
        assertThat(panel.evaluate("el => el.classList.contains('collapsed')")).isEqualTo(false);
        assertThat(expandBtn.isVisible()).isFalse();

        // State survives reload
        page.reload();
        assertThat(panel.evaluate("el => el.classList.contains('collapsed')")).isEqualTo(false);
    }

    @Test
    void meshPanel_viewSwitching_updatesActiveButton() {
        page.navigate(BASE_URL + "/app/");

        // Clear view preference to get predictable start state
        page.evaluate("() => { localStorage.removeItem('mesh-view'); }");
        page.reload();

        var overviewBtn = page.locator(".mesh-view-btn[data-view='overview']");
        var channelBtn  = page.locator(".mesh-view-btn[data-view='channel']");
        var feedBtn     = page.locator(".mesh-view-btn[data-view='feed']");

        // Overview is active by default
        assertThat(overviewBtn.evaluate("el => el.classList.contains('active')")).isEqualTo(true);

        // Click Channel
        channelBtn.click();
        assertThat(channelBtn.evaluate("el => el.classList.contains('active')")).isEqualTo(true);
        assertThat(overviewBtn.evaluate("el => el.classList.contains('active')")).isEqualTo(false);

        // Click Feed
        feedBtn.click();
        assertThat(feedBtn.evaluate("el => el.classList.contains('active')")).isEqualTo(true);
        assertThat(channelBtn.evaluate("el => el.classList.contains('active')")).isEqualTo(false);

        // View persists across reload
        page.reload();
        assertThat(feedBtn.evaluate("el => el.classList.contains('active')")).isEqualTo(true);
    }

    @Test
    void meshPanel_emptyState_showsMessageNotError() {
        page.navigate(BASE_URL + "/app/");

        // Wait for the panel's init() to complete (fetch /api/mesh/config and start polling)
        page.waitForFunction(
            "() => document.getElementById('mesh-body').textContent.trim().length > 0",
            null,
            new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(5000));

        var body = page.locator("#mesh-body");

        // Overview view
        page.locator(".mesh-view-btn[data-view='overview']").click();
        var overviewText = body.textContent().trim();
        assertThat(overviewText).isNotEmpty();
        assertThat(overviewText.toLowerCase()).doesNotContain("uncaught");
        assertThat(overviewText.toLowerCase()).doesNotContain("typeerror");

        // Channel view
        page.locator(".mesh-view-btn[data-view='channel']").click();
        assertThat(body.textContent().trim()).isNotEmpty();

        // Feed view
        page.locator(".mesh-view-btn[data-view='feed']").click();
        assertThat(body.textContent().trim()).isNotEmpty();
    }
}
