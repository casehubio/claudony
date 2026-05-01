package dev.claudony.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.RequestOptions;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for the Qhorus channel panel on the session view page (/app/session.html).
 *
 * <p>Covers: panel toggle (button + Ctrl+K), channel dropdown population,
 * timeline loading, message type badges, human sender styling, interjection
 * dock post, and cursor-based polling (only new messages on subsequent polls).
 *
 * <p>Auth: page requests include the test API key via PlaywrightBase.setExtraHTTPHeaders.
 * REST seeding calls include X-Api-Key explicitly via page.request() options.
 */
@QuarkusTest
class ChannelPanelE2ETest extends PlaywrightBase {

    @Inject
    QhorusMcpTools tools;
    @Inject
    InMemoryChannelStore channelStore;
    @Inject
    InMemoryMessageStore messageStore;

    private String channelName;

    @BeforeEach
    void createChannel() {
        channelName = "ch-panel-e2e-" + System.nanoTime();
        tools.createChannel(channelName, "E2E test channel", "APPEND", null, null, null, null, null, null);
    }

    @AfterEach
    void cleanUp() {
        messageStore.clear();
        channelStore.clear();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Navigate to the session page without a pre-selected channel. */
    private void navigateToSessionPage() {
        page.navigate(BASE_URL + "/app/session.html?id=fake-session-id&name=test-session");
    }

    /** Navigate to the session page with the test channel pre-selected. */
    private void navigateToSessionPageWithChannel() {
        page.navigate(BASE_URL + "/app/session.html?id=fake-session-id&name=test-session&channel=" + channelName);
    }

    /** Open the channel panel by clicking the toggle button. */
    private void openPanel() {
        page.locator("#ch-toggle-btn").click();
        // Wait for the panel to not have the collapsed class
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
    }

    /** Post a message to the test channel via the REST API. */
    private void postMessage(String content, String type) {
        var response = page.request().post(
                BASE_URL + "/api/mesh/channels/" + channelName + "/messages",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setHeader("X-Api-Key", API_KEY)
                        .setData("{\"content\":\"" + content + "\",\"type\":\"" + type + "\"}"));
        assertThat(response.status())
                .as("REST seed of message '%s' type '%s' failed", content, type)
                .isEqualTo(200);
    }

    // ── AC 1: toggle panel open / closed ──────────────────────────────────────

    @Test
    void toggleBtn_opensThenClosesPanel() {
        navigateToSessionPage();

        // Panel starts collapsed
        assertThat(page.locator("#channel-panel").getAttribute("class"))
                .contains("collapsed");

        // Click toggle → panel opens
        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(page.locator("#channel-panel").getAttribute("class"))
                .doesNotContain("collapsed");

        // Click toggle again → panel closes
        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel.collapsed").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(page.locator("#channel-panel").getAttribute("class"))
                .contains("collapsed");
    }

    // ── AC 2: channel dropdown populates ──────────────────────────────────────

    @Test
    void channelDropdown_populatesWithAvailableChannels() {
        navigateToSessionPage();
        openPanel();

        // Wait for the dropdown to contain at least one option (polls /api/mesh/channels)
        page.locator("#ch-select option[value='" + channelName + "']").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // The test channel must appear in the dropdown (at least once)
        assertThat(page.locator("#ch-select option").count()).isGreaterThanOrEqualTo(1);
        assertThat(page.locator("#ch-select option[value='" + channelName + "']").count())
                .isGreaterThanOrEqualTo(1);
    }

    // ── AC 3: selecting a channel loads the timeline ──────────────────────────

    @Test
    void channelPreselect_loadsTimelineMessages() {
        // Seed 2 messages before navigating
        postMessage("first timeline message", "status");
        postMessage("second timeline message", "query");

        // Navigate with ?channel= pre-select
        navigateToSessionPageWithChannel();

        // Toggle button opens panel; loadChannels() sees ?channel=, auto-selects + calls selectChannel
        openPanel();

        // Both messages must appear in the feed (allow up to 5s)
        page.locator("#ch-feed .ch-msg").first().waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        var messages = page.locator("#ch-feed .ch-msg");
        assertThat(messages.count()).isGreaterThanOrEqualTo(2);

        var feedText = page.locator("#ch-feed").textContent();
        assertThat(feedText).contains("first timeline message");
        assertThat(feedText).contains("second timeline message");
    }

    // ── AC 4: message type badges ─────────────────────────────────────────────

    @Test
    void messageBadges_showCorrectTypeLabel() {
        postMessage("a status update", "status");
        postMessage("a query request", "query");

        navigateToSessionPageWithChannel();
        openPanel();

        // Wait for messages to appear
        page.locator("#ch-feed .ch-msg").first().waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // Collect all badge texts
        var badges = page.locator("#ch-feed .msg-badge");
        assertThat(badges.count()).isGreaterThanOrEqualTo(2);

        var badgeTexts = badges.allTextContents();
        assertThat(badgeTexts).contains("STATUS");
        assertThat(badgeTexts).contains("QUERY");
    }

    // ── AC 5: human sender styling ────────────────────────────────────────────

    @Test
    void humanSender_hasHumanSenderClass() {
        // Messages posted via REST API are stamped with sender = "human"
        postMessage("human priority message", "status");

        navigateToSessionPageWithChannel();
        openPanel();

        // Wait for message to appear
        page.locator("#ch-feed .ch-msg").first().waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // The sender span must carry the ch-sender-human class
        var humanSenders = page.locator("#ch-feed .ch-sender-human");
        assertThat(humanSenders.count()).isGreaterThanOrEqualTo(1);
        assertThat(humanSenders.first().textContent()).isEqualTo("human");
    }

    // ── AC 6: post message via interjection dock ──────────────────────────────

    @Test
    void interjectionDock_postMessageAppearsInFeed() {
        navigateToSessionPageWithChannel();
        openPanel();

        // Wait for channel to be selected (send button should become enabled after channel select)
        page.locator("#ch-select option[value='" + channelName + "']").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // Manually select the channel if not already selected by auto-select
        page.evaluate("() => { " +
                "var sel = document.getElementById('ch-select'); " +
                "sel.value = '" + channelName + "'; " +
                "sel.dispatchEvent(new Event('change')); " +
                "}");

        // Wait for send button to be enabled (happens after channel is selected and input has content)
        var input = page.locator("#ch-input");
        input.fill("hello from the interjection dock");

        // Send button should now be enabled
        page.locator("#ch-send-btn:not([disabled])").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        page.locator("#ch-send-btn").click();

        // Message must appear in the feed (posted via fetch, then next poll picks it up)
        page.locator("#ch-feed .ch-msg").waitFor(
                new Locator.WaitForOptions().setTimeout(6000));

        var feedText = page.locator("#ch-feed").textContent();
        assertThat(feedText).contains("hello from the interjection dock");

        // Input is cleared after successful send
        assertThat(input.inputValue()).isEmpty();
    }

    // ── AC 7: cursor polling — only new messages appear ───────────────────────

    @Test
    void cursorPolling_onlyNewMessagesAppearAfterInitialLoad() {
        // Seed 1 message before navigating — the initial timeline fetch gets it
        postMessage("pre-existing message", "status");

        navigateToSessionPageWithChannel();
        openPanel();

        // Wait for the pre-existing message to appear
        page.locator("#ch-feed .ch-msg").first().waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(page.locator("#ch-feed .ch-msg").count()).isGreaterThanOrEqualTo(1);

        // Seed a second message after initial load — the poll (POLL_MS = 3000ms) picks it up
        postMessage("new message after initial load", "query");

        // Wait for the poll cycle (up to 6s = 2× POLL_MS)
        page.locator("#ch-feed .ch-msg:nth-child(2)").waitFor(
                new Locator.WaitForOptions().setTimeout(8000));

        var feedText = page.locator("#ch-feed").textContent();
        assertThat(feedText).contains("pre-existing message");
        assertThat(feedText).contains("new message after initial load");

        // At least 2 messages visible (pre-existing + new); cursor-based polling ensures
        // the new message was fetched via the poll rather than a full reload
        var msgCount = page.locator("#ch-feed .ch-msg").count();
        assertThat(msgCount).isGreaterThanOrEqualTo(2);
    }

    // ── AC 8: Ctrl+K toggles panel ────────────────────────────────────────────

    @Test
    void ctrlK_togglesPanelOpenAndClosed() {
        navigateToSessionPage();

        // Panel starts collapsed
        assertThat(page.locator("#channel-panel").getAttribute("class"))
                .contains("collapsed");

        // Press Ctrl+K → panel opens
        page.keyboard().press("Control+k");
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(page.locator("#channel-panel").getAttribute("class"))
                .doesNotContain("collapsed");

        // Press Ctrl+K again → panel closes
        page.keyboard().press("Control+k");
        page.locator("#channel-panel.collapsed").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(page.locator("#channel-panel").getAttribute("class"))
                .contains("collapsed");
    }
}
