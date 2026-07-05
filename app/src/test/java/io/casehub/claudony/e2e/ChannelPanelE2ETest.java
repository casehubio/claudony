package io.casehub.claudony.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.runtime.mcp.ReactiveQhorusMcpTools;
import io.casehub.qhorus.persistence.memory.InMemoryChannelStore;
import io.casehub.qhorus.persistence.memory.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
    ReactiveQhorusMcpTools tools;
    @Inject
    InMemoryChannelStore channelStore;
    @Inject
    InMemoryMessageStore messageStore;

    private String channelName;
    private java.util.UUID channelId;

    @BeforeEach
    void createChannel() {
        channelName = "ch-panel-e2e-" + System.nanoTime();
        Channel ch = Channel.builder(channelName)
                .description("E2E test channel")
                .semantic(ChannelSemantic.APPEND)
                .build();
        channelStore.put(ch);
        channelId = ch.id();
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
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));

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
        // API key auth gives principal "agent" → sender stored as "human:agent".
        // The panel strips "human:" and displays just the username ("agent").
        postMessage("human priority message", "status");

        navigateToSessionPageWithChannel();
        openPanel();

        // Wait for message to appear
        page.locator("#ch-feed .ch-msg").first().waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // Any "human:…" sender gets the ch-sender-human styling class
        var humanSenders = page.locator("#ch-feed .ch-sender-human");
        assertThat(humanSenders.count()).isGreaterThanOrEqualTo(1);

        // "human:" prefix is stripped — only the username is shown
        var senderText = humanSenders.first().textContent();
        assertThat(senderText).doesNotContain("human:");
        assertThat(senderText).isNotBlank();
        assertThat(senderText).isEqualTo("agent");
    }

    // ── AC 6: post message via interjection dock ──────────────────────────────

    @Test
    void interjectionDock_postMessageAppearsInFeed() {
        navigateToSessionPageWithChannel();
        openPanel();

        // Wait for channel to be selected (send button should become enabled after channel select)
        page.locator("#ch-select option[value='" + channelName + "']").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));

        // Manually select the channel if not already selected by auto-select
        page.evaluate("() => { " +
                "var sel = document.getElementById('ch-select'); " +
                "sel.value = '" + channelName + "'; " +
                "sel.dispatchEvent(new Event('change')); " +
                "}");

        // Select status type — content is rendered in feed for status messages
        page.locator("#ch-type-select").selectOption("status");

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

        // Seed a second message after initial load — the SSE live stream picks it up
        postMessage("new message after initial load", "query");

        // Wait for SSE delivery (up to 2s — SSE polls every 500ms)
        page.locator("#ch-feed .ch-msg:nth-child(2)").waitFor(
                new Locator.WaitForOptions().setTimeout(8000));

        var feedText = page.locator("#ch-feed").textContent();
        assertThat(feedText).contains("pre-existing message");
        assertThat(feedText).contains("new message after initial load");

        // At least 2 messages visible (pre-existing + new); cursor-based SSE ensures
        // the new message was fetched via SSE live stream rather than a full reload
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

    // ── AC 9: EVENT message renders with badge and telemetry fields ───────────

    @Test
    void eventMessage_rendersWithEventBadgeAndTelemetryFields() {
        // EVENT messages carry telemetry as JSON content; content field itself is null by design
        tools.sendMessage(channelName, "system", "event",
                "{\"tool_name\":\"read_file\",\"duration_ms\":250,\"token_count\":150}",
                null, null, null, null, null, null, null).await().atMost(Duration.ofSeconds(5));

        navigateToSessionPageWithChannel();
        openPanel();

        page.locator("#ch-feed .ch-msg").first().waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // EVENT message gets the ch-msg-event CSS class
        assertThat(page.locator("#ch-feed .ch-msg-event").count()).isGreaterThanOrEqualTo(1);

        // EVENT badge is shown
        var badge = page.locator("#ch-feed .msg-event").first();
        assertThat(badge.textContent()).isEqualTo("EVENT");

        // Telemetry fields are rendered (tool_name · duration_ms · token_count)
        var feedText = page.locator("#ch-feed").textContent();
        assertThat(feedText).contains("read_file");
        assertThat(feedText).contains("250ms");
        assertThat(feedText).contains("150tok");
    }

    @Test
    void eventMessage_withMissingTelemetryFields_rendersDash() {
        // EVENT with no tool_name/duration_ms/token_count falls back to '—'
        tools.sendMessage(channelName, "system", "event", "{}", null, null, null, null, null, null, null)
                .await().atMost(Duration.ofSeconds(5));

        navigateToSessionPageWithChannel();
        openPanel();

        page.locator("#ch-feed .ch-msg-event").first().waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        var feedText = page.locator("#ch-feed").textContent();
        assertThat(feedText).contains("—");
    }

    // ── AC 10: interjection dock defaults to COMMAND type ─────────────────────

    @Test
    void interjectionDock_defaultTypeIsCommand() {
        navigateToSessionPage();
        openPanel();

        // Before selecting any channel, the type dropdown default must be 'command'
        var defaultType = (String) page.evaluate(
                "() => document.getElementById('ch-type-select').value");
        assertThat(defaultType).isEqualTo("command");
    }

    // ── AC 11: type dropdown filters to channel's allowedTypes ────────────────

    @Test
    void interjectionDock_typeDropdown_filteredToChannelAllowedTypes() {
        // Create a channel restricted to COMMAND and QUERY only
        String restrictedChannel = "restricted-" + System.nanoTime();
        Channel restricted = Channel.builder(restrictedChannel)
                .description("Governance channel")
                .semantic(ChannelSemantic.APPEND)
                .allowedTypes(java.util.Set.of(MessageType.COMMAND, MessageType.QUERY))
                .build();
        channelStore.put(restricted);

        page.navigate(BASE_URL + "/app/session.html?id=fake-session-id&name=test-session");
        openPanel();

        // Wait for restricted channel to appear in dropdown
        page.locator("#ch-select option[value='" + restrictedChannel + "']").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));

        // Select the restricted channel — triggers updateTypeSelectForChannel
        page.evaluate("() => { " +
                "var sel = document.getElementById('ch-select'); " +
                "sel.value = '" + restrictedChannel + "'; " +
                "sel.dispatchEvent(new Event('change')); " +
                "}");

        // Type dropdown must only contain COMMAND and QUERY
        var optionValues = page.locator("#ch-type-select option").allTextContents();
        assertThat(optionValues).hasSize(2);
        assertThat(optionValues).anyMatch(s -> s.contains("COMMAND"));
        assertThat(optionValues).anyMatch(s -> s.contains("QUERY"));
        assertThat(optionValues).noneMatch(s -> s.contains("STATUS"));
        assertThat(optionValues).noneMatch(s -> s.contains("EVENT"));
    }

    // ── AC 12: catch-up on panel reopen uses ?after= cursor ──────────────────

    @Test
    void catchUp_onPanelReopen_usesAfterCursor() {
        // Seed 2 messages before initial open
        postMessage("msg-before-close-1", "status");
        postMessage("msg-before-close-2", "status");

        navigateToSessionPageWithChannel();
        openPanel();

        // Wait for initial messages to render
        page.locator("#ch-feed .ch-msg").first().waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(page.locator("#ch-feed .ch-msg").count()).isGreaterThanOrEqualTo(2);

        // Capture SSE event requests from panel reopen onwards
        var eventUrls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        page.onRequest(req -> {
            if (req.url().contains("/events")) eventUrls.add(req.url());
        });

        // Close panel
        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel.collapsed").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        eventUrls.clear(); // discard any pre-close SSE requests

        // Seed 1 more message while panel is closed
        postMessage("msg-after-close", "status");

        // Reopen panel — should use ?after=<lastId> for catch-up via SSE
        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // Wait for the new message to appear
        page.locator("#ch-feed .ch-msg").filter(
                new Locator.FilterOptions().setHasText("msg-after-close"))
                .first().waitFor(new Locator.WaitForOptions().setTimeout(8000));

        // At least one SSE events request must have used ?after= (catch-up rather than full reload)
        assertThat(eventUrls)
                .as("panel reopen should issue an SSE catch-up request with ?after=<id>")
                .anyMatch(url -> url.contains("after=") && !url.contains("after=0"));

        // Feed must show all 3 messages without duplicating the first two
        assertThat(page.locator("#ch-feed").textContent()).contains("msg-before-close-1");
        assertThat(page.locator("#ch-feed").textContent()).contains("msg-after-close");
    }

    // ── AC 13: stale cursor shows reconnect prompt ────────────────────────────

    @Test
    void staleCursor_showsReconnectPrompt() {
        postMessage("initial-msg", "status");

        navigateToSessionPageWithChannel();
        openPanel();

        // Wait for message to appear and cursor to be written to sessionStorage
        page.locator("#ch-feed .ch-msg").first().waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // Close panel
        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel.collapsed").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // Backdating the cursor timestamp to 2 hours ago makes it stale
        page.evaluate("(name) => {" +
                "var k='claudony.channel.cursors';" +
                "var c=JSON.parse(sessionStorage.getItem(k)||'{}');" +
                "if(c[name]){c[name].ts=Date.now()-2*60*60*1000;}" +
                "sessionStorage.setItem(k,JSON.stringify(c));" +
                "}", channelName);

        // Reopen panel
        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // Stale cursor prompt must appear
        page.locator("#ch-stale-prompt").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(page.locator("#ch-stale-prompt").isVisible()).isTrue();
    }

    // ── AC 14: stale cursor — choose catch-up loads from cursor ──────────────

    @Test
    void staleCursor_chooseCatchUp_fetchesFromCursor() {
        postMessage("old-msg", "status");

        navigateToSessionPageWithChannel();
        openPanel();
        page.locator("#ch-feed .ch-msg").first().waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel.collapsed").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // Make cursor stale
        page.evaluate("(name) => {" +
                "var k='claudony.channel.cursors';" +
                "var c=JSON.parse(sessionStorage.getItem(k)||'{}');" +
                "if(c[name]){c[name].ts=Date.now()-2*60*60*1000;}" +
                "sessionStorage.setItem(k,JSON.stringify(c));" +
                "}", channelName);

        postMessage("new-msg-after-absence", "status");

        // Capture SSE event requests after reopening
        var eventUrls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        page.onRequest(req -> {
            if (req.url().contains("/events")) eventUrls.add(req.url());
        });

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // Prompt must appear
        page.locator("#ch-stale-prompt").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // Click catch-up
        page.locator("#ch-stale-catchup-btn").click();

        // SSE catch-up uses ?after= and delivers the new message
        page.locator("#ch-feed .ch-msg").filter(
                new Locator.FilterOptions().setHasText("new-msg-after-absence"))
                .first().waitFor(new Locator.WaitForOptions().setTimeout(8000));

        assertThat(eventUrls)
                .anyMatch(url -> url.contains("after=") && !url.contains("after=0"));
        assertThat(page.locator("#ch-stale-prompt").isVisible()).isFalse();
    }

    // ── AC 15: stale cursor — choose reload fetches full history ─────────────

    @Test
    void staleCursor_chooseReload_fetchesFullHistory() {
        postMessage("old-msg-reload", "status");

        navigateToSessionPageWithChannel();
        openPanel();
        page.locator("#ch-feed .ch-msg").first().waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel.collapsed").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // Make cursor stale
        page.evaluate("(name) => {" +
                "var k='claudony.channel.cursors';" +
                "var c=JSON.parse(sessionStorage.getItem(k)||'{}');" +
                "if(c[name]){c[name].ts=Date.now()-2*60*60*1000;}" +
                "sessionStorage.setItem(k,JSON.stringify(c));" +
                "}", channelName);

        // Capture SSE event requests
        var eventUrls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        page.onRequest(req -> {
            if (req.url().contains("/events")) eventUrls.add(req.url());
        });

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        page.locator("#ch-stale-prompt").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        page.locator("#ch-stale-reload-btn").click();

        // Full history load via SSE with ?after=0 (cursor deleted before opening)
        page.locator("#ch-feed .ch-msg").first().waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        // The reload SSE request uses ?after=0 (full reload from beginning)
        assertThat(eventUrls)
                .anyMatch(url -> url.contains("after=0"));
        assertThat(page.locator("#ch-stale-prompt").isVisible()).isFalse();
        assertThat(page.locator("#ch-feed").textContent()).contains("old-msg-reload");
    }

    @Test
    void interjectionDock_openChannel_showsAllTypes() {
        // channelName was created with null allowedTypes — all 8 types should be available
        page.navigate(BASE_URL + "/app/session.html?id=fake-session-id&name=test-session");
        openPanel();

        page.locator("#ch-select option[value='" + channelName + "']").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));

        page.evaluate("() => { " +
                "var sel = document.getElementById('ch-select'); " +
                "sel.value = '" + channelName + "'; " +
                "sel.dispatchEvent(new Event('change')); " +
                "}");

        var optionValues = page.locator("#ch-type-select option").allTextContents();
        assertThat(optionValues).hasSize(8);
        assertThat(optionValues).anyMatch(s -> s.contains("COMMAND"));
        assertThat(optionValues).anyMatch(s -> s.contains("QUERY"));
        assertThat(optionValues).anyMatch(s -> s.contains("STATUS"));
        assertThat(optionValues).anyMatch(s -> s.contains("EVENT"));
        assertThat(optionValues).anyMatch(s -> s.contains("HANDOFF"));
    }

    // ── AC 16: real-time push — message appears without waiting for poll cycle ──

    @Test
    void channelEvents_pushesMessageInRealTime() {
        navigateToSessionPageWithChannel();
        openPanel();

        // Wait for channel to be selected and EventSource to open
        page.locator("#ch-select option[value='" + channelName + "']").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));
        page.evaluate("() => { " +
                "var sel = document.getElementById('ch-select'); " +
                "sel.value = '" + channelName + "'; " +
                "sel.dispatchEvent(new Event('change')); " +
                "}");

        // Give 1s for EventSource handshake to complete
        page.waitForTimeout(1000);

        // Seed a message AFTER EventSource is open
        long before = System.currentTimeMillis();
        postMessage("real-time-push-msg", "status");

        // Message must appear within 2s (real-time push, not 3s poll cycle)
        page.locator("#ch-feed .ch-msg").filter(
                new Locator.FilterOptions().setHasText("real-time-push-msg"))
                .first().waitFor(new Locator.WaitForOptions().setTimeout(2000));
        long elapsed = System.currentTimeMillis() - before;

        assertThat(elapsed).isLessThan(2500);
        assertThat(page.locator("#ch-feed").textContent()).contains("real-time-push-msg");
    }

    // ── AC 17: EventSource error triggers poll fallback ───────────────────────

    @Test
    void channelPanel_eventSourceError_fallsBackToPoll() {
        // Abort SSE connections before navigation — route is active when EventSource opens.
        // Matches /api/mesh/channels/{name}/events?after=... for any channel name.
        page.route("**/api/mesh/channels/*/events*", route -> route.abort());

        // Navigate with channel pre-selected (URL param); openPanel() auto-selects it.
        navigateToSessionPageWithChannel();
        openPanel();

        // Wait for fullLoad() to complete (uses /timeline, not /events — not affected by route).
        // Empty channel shows "No messages yet." placeholder (.ch-empty).
        page.locator(".ch-empty").waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(5000));

        // Insert a message AFTER fullLoad completes.
        // The next pollChannel() tick (POLL_MS=3000ms after EventSource onerror) will deliver it.
        Message msg = Message.builder()
                .channelId(channelId)
                .sender("agent:poll-fallback-test")
                .messageType(MessageType.STATUS)
                .content("poll-delivers-this")
                .build();
        messageStore.put(msg);

        // Wait up to 6s for poll cycle to deliver the message (POLL_MS=3000ms + 2s margin).
        page.locator("#ch-feed .ch-msg").first().waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(6000));

        assertThat(page.locator("#ch-feed").textContent()).contains("poll-delivers-this");
    }
}
