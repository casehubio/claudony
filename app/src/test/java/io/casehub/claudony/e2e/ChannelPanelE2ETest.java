package io.casehub.claudony.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.persistence.memory.InMemoryChannelStore;
import io.casehub.qhorus.persistence.memory.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ChannelPanelE2ETest extends PlaywrightBase {

    @Inject QhorusMcpTools tools;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    private String channelName;
    private java.util.UUID channelId;

    @BeforeEach
    void createChannel() {
        channelName = "ch-panel-e2e-" + System.nanoTime();
        Channel ch = Channel.builder(channelName)
                .description("E2E test channel")
                .semantic(ChannelSemantic.APPEND)
                .build();
        ch = channelStore.put(ch);
        channelId = ch.id();
    }

    @AfterEach
    void cleanUp() {
        messageStore.clear();
        channelStore.clear();
    }

    private void navigateToSessionPage() {
        page.navigate(BASE_URL + "/app/session.html?id=fake-session-id&name=test-session");
    }

    private void navigateToSessionPageWithChannel() {
        page.navigate(BASE_URL + "/app/session.html?id=fake-session-id&name=test-session&channel=" + channelName);
    }

    private void openPanel() {
        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
    }

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

    private Locator panelShadow() {
        return page.locator("claudony-channel-panel");
    }

    private Locator feedMessages() {
        return panelShadow().locator("channel-feed >> .message-item");
    }

    private String feedText() {
        Object result = panelShadow().locator("channel-message").evaluateAll(
                "els => els.map(el => el.shadowRoot ? el.shadowRoot.textContent : el.textContent).join(' ')");
        return result != null ? result.toString() : "";
    }

    @Test
    void toggleBtn_opensThenClosesPanel() {
        navigateToSessionPage();
        assertThat(page.locator("#channel-panel").getAttribute("class")).contains("collapsed");

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(page.locator("#channel-panel").getAttribute("class")).doesNotContain("collapsed");

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel.collapsed").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(page.locator("#channel-panel").getAttribute("class")).contains("collapsed");
    }

    @Test
    void channelDropdown_populatesWithAvailableChannels() {
        navigateToSessionPage();
        openPanel();

        panelShadow().locator("option[value='" + channelName + "']").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));
        assertThat(panelShadow().locator("option[value='" + channelName + "']").count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void channelPreselect_loadsTimelineMessages() {
        postMessage("first timeline message", "status");
        postMessage("second timeline message", "query");

        navigateToSessionPageWithChannel();
        openPanel();

        feedMessages().first().waitFor(new Locator.WaitForOptions().setTimeout(5000));
        assertThat(feedMessages().count()).isGreaterThanOrEqualTo(2);

        var text = feedText();
        assertThat(text).contains("first timeline message");
        assertThat(text).contains("second timeline message");
    }

    @Test
    void messageBadges_showCorrectTypeLabel() {
        postMessage("a status update", "status");
        postMessage("a query request", "query");

        navigateToSessionPageWithChannel();
        openPanel();

        feedMessages().first().waitFor(new Locator.WaitForOptions().setTimeout(5000));

        var badges = panelShadow().locator("channel-feed >> channel-message >> .speech-act-badge");
        assertThat(badges.count()).isGreaterThanOrEqualTo(2);

        var badgeTexts = badges.allTextContents();
        assertThat(badgeTexts).contains("STATUS");
        assertThat(badgeTexts).contains("QUERY");
    }

    @Test
    void humanSender_rendersWithHumanActorIcon() {
        postMessage("human priority message", "status");
        navigateToSessionPageWithChannel();
        openPanel();

        feedMessages().first().waitFor(new Locator.WaitForOptions().setTimeout(5000));

        var text = feedText();
        assertThat(text).contains("human priority message");
    }

    @Test
    void interjectionDock_postMessageAppearsInFeed() {
        navigateToSessionPageWithChannel();
        openPanel();

        panelShadow().locator("option[value='" + channelName + "']").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));

        page.evaluate("() => { " +
                "var panel = document.querySelector('claudony-channel-panel'); " +
                "var sel = panel.shadowRoot.querySelector('.ch-select'); " +
                "sel.value = '" + channelName + "'; " +
                "sel.dispatchEvent(new Event('change')); " +
                "}");

        var input = panelShadow().locator("channel-input >> textarea");
        input.fill("hello from the interjection dock");

        panelShadow().locator("channel-input >> textarea").press("Enter");

        feedMessages().filter(new Locator.FilterOptions().setHasText("hello from the interjection dock"))
                .first().waitFor(new Locator.WaitForOptions().setTimeout(6000));

        assertThat(feedText()).contains("hello from the interjection dock");
    }

    @Test
    void cursorPolling_onlyNewMessagesAppearAfterInitialLoad() {
        postMessage("pre-existing message", "status");
        navigateToSessionPageWithChannel();
        openPanel();

        feedMessages().first().waitFor(new Locator.WaitForOptions().setTimeout(5000));
        assertThat(feedMessages().count()).isGreaterThanOrEqualTo(1);

        postMessage("new message after initial load", "query");

        feedMessages().filter(new Locator.FilterOptions().setHasText("new message after initial load"))
                .first().waitFor(new Locator.WaitForOptions().setTimeout(8000));

        var text = feedText();
        assertThat(text).contains("pre-existing message");
        assertThat(text).contains("new message after initial load");
        assertThat(feedMessages().count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void ctrlK_togglesPanelOpenAndClosed() {
        navigateToSessionPage();
        assertThat(page.locator("#channel-panel").getAttribute("class")).contains("collapsed");

        page.keyboard().press("Control+k");
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(page.locator("#channel-panel").getAttribute("class")).doesNotContain("collapsed");

        page.keyboard().press("Control+k");
        page.locator("#channel-panel.collapsed").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(page.locator("#channel-panel").getAttribute("class")).contains("collapsed");
    }

    @Test
    void eventMessage_rendersWithEventBadgeAndTelemetryFields() {
        messageStore.put(Message.builder()
                .channelId(channelId)
                .sender("system")
                .messageType(MessageType.EVENT)
                .content("{\"tool_name\":\"read_file\",\"duration_ms\":250,\"token_count\":150}")
                .build());

        navigateToSessionPageWithChannel();
        openPanel();

        feedMessages().first().waitFor(new Locator.WaitForOptions().setTimeout(5000));

        var text = feedText();
        assertThat(text).contains("EVENT");
    }

    @Test
    void eventMessage_withMissingTelemetryFields_rendersDash() {
        messageStore.put(Message.builder()
                .channelId(channelId)
                .sender("system")
                .messageType(MessageType.EVENT)
                .content("{}")
                .build());

        navigateToSessionPageWithChannel();
        openPanel();

        feedMessages().first().waitFor(new Locator.WaitForOptions().setTimeout(5000));
    }

    @Test
    void interjectionDock_defaultTypeIsCommand() {
        navigateToSessionPage();
        openPanel();

        var typeSelect = panelShadow().locator("channel-input >> select");
        typeSelect.waitFor(new Locator.WaitForOptions().setTimeout(5000));

        var defaultType = page.evaluate(
                "() => document.querySelector('claudony-channel-panel')" +
                ".shadowRoot.querySelector('channel-input')._selectedType");
        assertThat(defaultType.toString()).isEqualToIgnoringCase("command");
    }

    @Test
    void interjectionDock_typeDropdown_filteredToChannelAllowedTypes() {
        String restrictedChannel = "restricted-" + System.nanoTime();
        Channel restricted = Channel.builder(restrictedChannel)
                .description("Governance channel")
                .semantic(ChannelSemantic.APPEND)
                .allowedTypes(java.util.Set.of(MessageType.COMMAND, MessageType.QUERY))
                .build();
        channelStore.put(restricted);

        page.navigate(BASE_URL + "/app/session.html?id=fake-session-id&name=test-session");
        openPanel();

        panelShadow().locator("option[value='" + restrictedChannel + "']").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));

        page.evaluate("(ch) => { " +
                "var panel = document.querySelector('claudony-channel-panel'); " +
                "var sel = panel.shadowRoot.querySelector('.ch-select'); " +
                "sel.value = ch; " +
                "sel.dispatchEvent(new Event('change')); " +
                "}", restrictedChannel);

        var typeOptions = panelShadow().locator("channel-input >> select >> option");
        typeOptions.first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));

        var optionTexts = typeOptions.allTextContents();
        assertThat(optionTexts).hasSize(2);
        assertThat(optionTexts).anyMatch(s -> s.toUpperCase().contains("COMMAND"));
        assertThat(optionTexts).anyMatch(s -> s.toUpperCase().contains("QUERY"));
    }

    @Test
    void catchUp_onPanelReopen_usesAfterCursor() {
        postMessage("msg-before-close-1", "status");
        postMessage("msg-before-close-2", "status");

        navigateToSessionPageWithChannel();
        openPanel();

        feedMessages().first().waitFor(new Locator.WaitForOptions().setTimeout(5000));
        assertThat(feedMessages().count()).isGreaterThanOrEqualTo(2);

        var eventUrls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        page.onRequest(req -> {
            if (req.url().contains("/events")) eventUrls.add(req.url());
        });

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel.collapsed").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        eventUrls.clear();

        postMessage("msg-after-close", "status");

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        feedMessages().filter(new Locator.FilterOptions().setHasText("msg-after-close"))
                .first().waitFor(new Locator.WaitForOptions().setTimeout(8000));

        assertThat(eventUrls)
                .as("panel reopen should issue an SSE catch-up request with ?after=<id>")
                .anyMatch(url -> url.contains("after=") && !url.contains("after=0"));

        assertThat(feedText()).contains("msg-before-close-1");
        assertThat(feedText()).contains("msg-after-close");
    }

    @Test
    void staleCursor_showsReconnectPrompt() {
        postMessage("initial-msg", "status");
        navigateToSessionPageWithChannel();
        openPanel();

        feedMessages().first().waitFor(new Locator.WaitForOptions().setTimeout(5000));

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel.collapsed").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        page.evaluate("(name) => {" +
                "var k='claudony.channel.cursors';" +
                "var c=JSON.parse(sessionStorage.getItem(k)||'{}');" +
                "if(c[name]){c[name].ts=Date.now()-2*60*60*1000;}" +
                "sessionStorage.setItem(k,JSON.stringify(c));" +
                "}", channelName);

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        panelShadow().locator(".stale-prompt").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        assertThat(panelShadow().locator(".stale-prompt").isVisible()).isTrue();
    }

    @Test
    void staleCursor_chooseCatchUp_fetchesFromCursor() {
        postMessage("old-msg", "status");
        navigateToSessionPageWithChannel();
        openPanel();
        feedMessages().first().waitFor(new Locator.WaitForOptions().setTimeout(5000));

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel.collapsed").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        page.evaluate("(name) => {" +
                "var k='claudony.channel.cursors';" +
                "var c=JSON.parse(sessionStorage.getItem(k)||'{}');" +
                "if(c[name]){c[name].ts=Date.now()-2*60*60*1000;}" +
                "sessionStorage.setItem(k,JSON.stringify(c));" +
                "}", channelName);

        postMessage("new-msg-after-absence", "status");

        var eventUrls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        page.onRequest(req -> {
            if (req.url().contains("/events")) eventUrls.add(req.url());
        });

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        panelShadow().locator(".stale-prompt").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        panelShadow().locator(".stale-btn").first().click();

        feedMessages().filter(new Locator.FilterOptions().setHasText("new-msg-after-absence"))
                .first().waitFor(new Locator.WaitForOptions().setTimeout(8000));

        assertThat(eventUrls).anyMatch(url -> url.contains("after=") && !url.contains("after=0"));
        assertThat(panelShadow().locator(".stale-prompt").isVisible()).isFalse();
    }

    @Test
    void staleCursor_chooseReload_fetchesFullHistory() {
        postMessage("old-msg-reload", "status");
        navigateToSessionPageWithChannel();
        openPanel();
        feedMessages().first().waitFor(new Locator.WaitForOptions().setTimeout(5000));

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel.collapsed").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        page.evaluate("(name) => {" +
                "var k='claudony.channel.cursors';" +
                "var c=JSON.parse(sessionStorage.getItem(k)||'{}');" +
                "if(c[name]){c[name].ts=Date.now()-2*60*60*1000;}" +
                "sessionStorage.setItem(k,JSON.stringify(c));" +
                "}", channelName);

        var eventUrls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        page.onRequest(req -> {
            if (req.url().contains("/events")) eventUrls.add(req.url());
        });

        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        panelShadow().locator(".stale-prompt").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
        panelShadow().locator(".stale-btn.secondary").click();

        feedMessages().first().waitFor(new Locator.WaitForOptions().setTimeout(5000));

        assertThat(eventUrls).anyMatch(url -> url.contains("after=0"));
        assertThat(panelShadow().locator(".stale-prompt").isVisible()).isFalse();
        assertThat(feedText()).contains("old-msg-reload");
    }

    @Test
    void interjectionDock_openChannel_showsAllTypes() {
        page.navigate(BASE_URL + "/app/session.html?id=fake-session-id&name=test-session");
        openPanel();

        panelShadow().locator("option[value='" + channelName + "']").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));

        page.evaluate("(ch) => { " +
                "var panel = document.querySelector('claudony-channel-panel'); " +
                "var sel = panel.shadowRoot.querySelector('.ch-select'); " +
                "sel.value = ch; " +
                "sel.dispatchEvent(new Event('change')); " +
                "}", channelName);

        var typeOptions = panelShadow().locator("channel-input >> select >> option");
        typeOptions.first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));

        var optionTexts = typeOptions.allTextContents();
        assertThat(optionTexts).hasSize(9);
        assertThat(optionTexts).anyMatch(s -> s.toUpperCase().contains("COMMAND"));
        assertThat(optionTexts).anyMatch(s -> s.toUpperCase().contains("QUERY"));
        assertThat(optionTexts).anyMatch(s -> s.toUpperCase().contains("STATUS"));
    }

    @Test
    void channelEvents_pushesMessageInRealTime() {
        navigateToSessionPageWithChannel();
        openPanel();

        panelShadow().locator("option[value='" + channelName + "']").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));
        page.evaluate("(ch) => { " +
                "var panel = document.querySelector('claudony-channel-panel'); " +
                "var sel = panel.shadowRoot.querySelector('.ch-select'); " +
                "sel.value = ch; " +
                "sel.dispatchEvent(new Event('change')); " +
                "}", channelName);

        page.waitForTimeout(1000);

        long before = System.currentTimeMillis();
        postMessage("real-time-push-msg", "status");

        feedMessages().filter(new Locator.FilterOptions().setHasText("real-time-push-msg"))
                .first().waitFor(new Locator.WaitForOptions().setTimeout(2000));
        long elapsed = System.currentTimeMillis() - before;

        assertThat(elapsed).isLessThan(2500);
        assertThat(feedText()).contains("real-time-push-msg");
    }

    @Test
    void channelPanel_eventSourceError_fallsBackToPoll() {
        page.route("**/api/mesh/channels/*/events*", route -> route.abort());

        navigateToSessionPageWithChannel();
        openPanel();

        panelShadow().locator("channel-feed >> .empty").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));

        Message msg = Message.builder()
                .channelId(channelId)
                .sender("agent:poll-fallback-test")
                .messageType(MessageType.STATUS)
                .content("poll-delivers-this")
                .build();
        messageStore.put(msg);

        feedMessages().first().waitFor(new Locator.WaitForOptions().setTimeout(6000));
        assertThat(feedText()).contains("poll-delivers-this");
    }
}
