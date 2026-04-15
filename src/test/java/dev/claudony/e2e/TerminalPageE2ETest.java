package dev.claudony.e2e;

import com.microsoft.playwright.Locator;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test for the terminal page (/app/session.html).
 *
 * <p>Tests page structure only — WebSocket connections are not authenticated via extra headers
 * (browsers don't support custom headers on WebSocket upgrades). The WebSocket fails and
 * terminal.js degrades gracefully, showing "reconnecting" in the status badge.
 *
 * <p>Full terminal I/O testing (connecting to a live tmux session, verifying output,
 * PROXY resize) is deferred to the "Expand E2E coverage" epic.
 */
@QuarkusTest
class TerminalPageE2ETest extends PlaywrightBase {

    @Test
    void terminalPage_loadsStructure_andDegracefullyHandlesFailedWebSocket() {
        // Navigate with a fake session ID — the page loads, WebSocket fails (no auth),
        // terminal.js shows reconnecting state
        page.navigate(BASE_URL + "/app/session.html?id=fake-id&name=test-terminal");

        // xterm.js container must be rendered in the DOM
        assertThat(page.locator("#terminal-container").isVisible()).isTrue();

        // Status badge shows "reconnecting" after WebSocket close (terminal.js ws.onclose handler)
        // Wait up to 5s for the reconnect cycle to begin
        page.locator("#status-badge").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(page.locator("#status-badge").textContent()).contains("reconnecting");

        // Session name shown in the header
        assertThat(page.locator("#session-name").textContent()).isEqualTo("test-terminal");
    }

    @Test
    void proxyMode_resizeCallsProxyEndpoint() {
        var proxyPeerId = "test-proxy-peer-id";

        // Intercept the resize fetch before navigating — route handler captures the URL.
        // Respond with 200 so fetch() doesn't fail and leave pending requests.
        var capturedUrl = new java.util.concurrent.atomic.AtomicReference<String>();
        page.route("**/resize**", route -> {
            capturedUrl.set(route.request().url());
            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setStatus(200));
        });

        page.navigate(BASE_URL + "/app/session.html?id=fake-session&name=test&proxyPeer=" + proxyPeerId);

        // fitAddon.fit() may be a no-op in headless (terminal initialises at 80×24 and the
        // headless container computes the same dimensions). Force onResize by calling
        // terminal.resize() with dimensions that differ from the xterm.js default (80×24).
        // window._xtermTerminal is exposed by terminal.js for E2E test purposes.
        page.evaluate("() => { if (window._xtermTerminal) window._xtermTerminal.resize(100, 30); }");

        // Wait up to 3s for onResize → fetch to arrive
        var deadline = System.currentTimeMillis() + 3000;
        while (capturedUrl.get() == null && System.currentTimeMillis() < deadline) {
            page.waitForTimeout(100);
        }

        assertThat(capturedUrl.get())
                .as("In PROXY mode, resize must call /api/peers/{peerId}/sessions/ not /api/sessions/")
                .isNotNull()
                .contains("/api/peers/" + proxyPeerId + "/sessions/fake-session/resize");
    }
}
