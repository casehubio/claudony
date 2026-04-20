package dev.claudony.e2e;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Playwright E2E tests for the mesh panel interjection dock.
 *
 * Tests dock structure, disabled state, and channel selection sync.
 * Full send-and-see-message test deferred (requires live Qhorus agent activity).
 *
 * Run with: mvn test -Pe2e -Dtest=MeshInterjectionE2ETest
 */
@QuarkusTest
class MeshInterjectionE2ETest extends PlaywrightBase {

    @Test
    void interjectionDock_visibleInAllViews() {
        page.navigate(BASE_URL + "/app/");
        page.evaluate("() => localStorage.removeItem('mesh-view')");
        page.reload();

        // Wait for panel to initialise before clicking view buttons
        page.waitForFunction(
            "() => document.getElementById('mesh-body').textContent.trim().length > 0",
            null,
            new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(5000));

        var dock = page.locator("#mesh-dock");

        // Overview view
        page.locator(".mesh-view-btn[data-view='overview']").click();
        assertThat(dock.isVisible()).isTrue();
        assertThat(page.locator("#mesh-dock-channel").isVisible()).isTrue();
        assertThat(page.locator("#mesh-dock-type").isVisible()).isTrue();
        assertThat(page.locator("#mesh-dock-textarea").isVisible()).isTrue();
        assertThat(page.locator("#mesh-dock-send").isVisible()).isTrue();

        // Channel view
        page.locator(".mesh-view-btn[data-view='channel']").click();
        assertThat(dock.isVisible()).isTrue();

        // Feed view
        page.locator(".mesh-view-btn[data-view='feed']").click();
        assertThat(dock.isVisible()).isTrue();
    }

    @Test
    void interjectionDock_disabledWhenNoChannels() {
        page.navigate(BASE_URL + "/app/");

        // Wait for MeshPanel.init() to complete — poll has run at least once
        page.waitForFunction(
            "() => document.getElementById('mesh-body').textContent.trim().length > 0",
            null,
            new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(5000));

        // With no Qhorus agents active, channel select and send should be disabled
        var channelSelect = page.locator("#mesh-dock-channel");
        var sendBtn       = page.locator("#mesh-dock-send");

        assertThat(channelSelect.evaluate("el => el.disabled")).isEqualTo(true);
        assertThat(sendBtn.evaluate("el => el.disabled")).isEqualTo(true);
        assertThat(channelSelect.evaluate("el => el.options[0].text"))
            .isEqualTo("— no channels —");
    }

    @Test
    void interjectionDock_structureIsComplete() {
        page.navigate(BASE_URL + "/app/");

        // Verify all dock elements are present and the type select has all 5 options
        assertThat(page.locator("#mesh-dock").isVisible()).isTrue();
        assertThat(page.locator("#mesh-dock-channel").isVisible()).isTrue();
        assertThat(page.locator("#mesh-dock-type").isVisible()).isTrue();
        assertThat(page.locator("#mesh-dock-textarea").isVisible()).isTrue();
        assertThat(page.locator("#mesh-dock-send").isVisible()).isTrue();

        // Type select has the 5 required options
        var typeSelect = page.locator("#mesh-dock-type");
        assertThat(((Number) typeSelect.evaluate("el => el.options.length")).intValue()).isEqualTo(5);
        assertThat(typeSelect.evaluate("el => el.options[0].value")).isEqualTo("status");
        assertThat(typeSelect.evaluate("el => el.options[1].value")).isEqualTo("request");
        assertThat(typeSelect.evaluate("el => el.options[2].value")).isEqualTo("response");
        assertThat(typeSelect.evaluate("el => el.options[3].value")).isEqualTo("handoff");
        assertThat(typeSelect.evaluate("el => el.options[4].value")).isEqualTo("done");
    }
}
