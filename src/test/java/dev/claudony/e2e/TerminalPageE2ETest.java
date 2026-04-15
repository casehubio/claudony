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
}
