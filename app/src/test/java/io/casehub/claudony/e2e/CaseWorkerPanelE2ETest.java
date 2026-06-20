package io.casehub.claudony.e2e;

import com.microsoft.playwright.Locator;
import io.casehub.claudony.server.CaseEventBroadcaster;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for the case worker panel on the session view page (/app/session.html).
 *
 * <p>Covers: standalone session shows collapsed panel with placeholder,
 * CaseHub session auto-expands panel with all workers listed (active highlighted),
 * clicking a worker row navigates to that session and updates the highlight,
 * and SSE-specific behaviours: no polling, immediate render, push updates, EventSource cleanup.
 *
 * <p>Auth: page requests include the test API key via PlaywrightBase.setExtraHTTPHeaders.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CaseWorkerPanelE2ETest extends PlaywrightBase {

    @Inject
    SessionRegistry registry;

    @Inject
    CaseEventBroadcaster broadcaster;

    @AfterEach
    void cleanupSessions() {
        registry.all().stream().map(Session::id).toList().forEach(registry::remove);
    }

    // ── AC 1: standalone session — panel collapsed with placeholder ───────────

    @Test
    @Order(1)
    void standaloneSession_panelIsCollapsedWithPlaceholder() {
        var now = Instant.now();
        registry.register(new Session("e2e-standalone", "claudony-e2e-standalone", "/tmp", "claude",
                SessionStatus.IDLE, now, now, Optional.empty(), Optional.empty(), Optional.empty()));

        page.navigate(BASE_URL + "/app/session.html?id=e2e-standalone&name=e2e-standalone");
        page.waitForTimeout(1500);

        var casePanel = page.locator("#case-panel");
        assertThat(casePanel.getAttribute("class"))
                .as("Panel should be collapsed for standalone session")
                .contains("collapsed");

        // Toggle open and verify placeholder
        page.locator("#workers-toggle-btn").click();
        page.waitForTimeout(400);
        assertThat(page.locator("#case-panel").getAttribute("class"))
                .as("Panel should open on toggle")
                .doesNotContain("collapsed");

        var placeholder = page.locator(".case-panel-placeholder");
        assertThat(placeholder.count())
                .as("Placeholder should exist")
                .isGreaterThan(0);
        assertThat(placeholder.first().isVisible())
                .as("Placeholder should be visible")
                .isTrue();
        assertThat(placeholder.first().textContent())
                .as("Placeholder text should indicate no case assigned")
                .contains("No case assigned");
    }

    // ── AC 2: CaseHub session — panel auto-expands with workers ──────────────

    @Test
    @Order(2)
    void caseHubSession_panelAutoExpandsWithWorkers() {
        var now = Instant.now();
        var caseId = "e2e-case-001";
        registry.register(new Session("e2e-w1", "claudony-worker-w1", "/tmp", "claude",
                SessionStatus.ACTIVE, now.minusSeconds(30), now.minusSeconds(30),
                Optional.empty(), Optional.of(caseId), Optional.of("agent")));
        registry.register(new Session("e2e-w2", "claudony-worker-w2", "/tmp", "claude",
                SessionStatus.IDLE, now, now,
                Optional.empty(), Optional.of(caseId), Optional.of("coder")));

        page.navigate(BASE_URL + "/app/session.html?id=e2e-w1&name=agent");

        // SSE initial snapshot arrives immediately — wait for first worker row
        page.locator(".case-worker-row").first().waitFor(
                new Locator.WaitForOptions().setTimeout(2000));

        assertThat(page.locator("#case-panel").getAttribute("class"))
                .as("Panel should auto-expand for CaseHub session")
                .doesNotContain("collapsed");

        var rows = page.locator(".case-worker-row");
        assertThat(rows.count())
                .as("Both workers should be listed")
                .isEqualTo(2);

        assertThat(rows.nth(0).getAttribute("class"))
                .as("First worker (agent) should be highlighted as active")
                .contains("active-worker");
        assertThat(rows.nth(0).textContent())
                .as("First row should show agent role")
                .contains("agent");

        assertThat(rows.nth(1).getAttribute("class"))
                .as("Second worker (coder) should not be active")
                .doesNotContain("active-worker");
        assertThat(rows.nth(1).textContent())
                .as("Second row should show coder role")
                .contains("coder");
    }

    // ── AC 3: click worker row — URL updates and highlight shifts ────────────

    @Test
    @Order(3)
    void clickingWorker_updatesUrlAndHighlight() {
        var now = Instant.now();
        var caseId = "e2e-case-002";
        registry.register(new Session("e2e-c1", "claudony-worker-c1", "/tmp", "claude",
                SessionStatus.ACTIVE, now.minusSeconds(10), now.minusSeconds(10),
                Optional.empty(), Optional.of(caseId), Optional.of("planner")));
        registry.register(new Session("e2e-c2", "claudony-worker-c2", "/tmp", "claude",
                SessionStatus.IDLE, now, now,
                Optional.empty(), Optional.of(caseId), Optional.of("executor")));

        page.navigate(BASE_URL + "/app/session.html?id=e2e-c1&name=planner");

        // Wait for initial SSE render
        page.locator(".case-worker-row").first().waitFor(
                new Locator.WaitForOptions().setTimeout(2000));

        // Click the second worker row (executor)
        page.locator(".case-worker-row").nth(1).click();
        page.waitForTimeout(600);

        assertThat(page.url())
                .as("URL should update to executor session")
                .contains("e2e-c2");

        // Wait for SSE to push the updated worker list with new active highlight
        page.locator(".case-worker-row.active-worker").waitFor(
                new Locator.WaitForOptions().setTimeout(2000));

        assertThat(page.locator(".case-worker-row").nth(0).getAttribute("class"))
                .as("Planner should no longer be highlighted")
                .doesNotContain("active-worker");
        assertThat(page.locator(".case-worker-row").nth(1).getAttribute("class"))
                .as("Executor should now be highlighted")
                .contains("active-worker");
    }

    // ── AC 4: no polling interval — SSE only ─────────────────────────────────────

    @Test
    @Order(4)
    void noPollingInterval_forWorkerUpdates_inSource() throws Exception {
        // Regression guard: verify terminal.js does not use setInterval for worker updates
        var jsSource = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/resources/META-INF/resources/app/terminal.js")));
        assertThat(jsSource)
                .as("pollWorkers function must be removed from terminal.js")
                .doesNotContain("function pollWorkers");
        assertThat(jsSource)
                .as("No setInterval for pollWorkers must remain")
                .doesNotContain("setInterval(pollWorkers");
        assertThat(jsSource)
                .as("EventSource must be present")
                .contains("new EventSource");
    }

    // ── AC 5: panel shows workers immediately on SSE connect ─────────────────────

    @Test
    @Order(5)
    void casePanel_showsWorkers_immediately_onSSEConnect() {
        var now = Instant.now();
        var caseId = "e2e-sse-case-001";
        registry.register(new Session("sse-w1", "claudony-worker-sse-w1", "/tmp", "claude",
                SessionStatus.ACTIVE, now, now, Optional.empty(), Optional.of(caseId), Optional.of("analyst")));

        page.navigate(BASE_URL + "/app/session.html?id=sse-w1&name=analyst");

        // SSE initial snapshot — should appear much faster than 3s poll
        var rows = page.locator(".case-worker-row");
        rows.first().waitFor(new Locator.WaitForOptions().setTimeout(1500));

        assertThat(rows.count()).isEqualTo(1);
        assertThat(rows.first().textContent()).contains("analyst");
    }

    // ── AC 6: panel updates on SSE push without polling ──────────────────────────

    @Test
    @Order(6)
    void casePanel_updatesWorkers_onSSEPush() throws Exception {
        var now = Instant.now();
        var caseId = "e2e-sse-case-002";
        registry.register(new Session("sse-upd-w1", "claudony-worker-sse-upd-w1", "/tmp", "claude",
                SessionStatus.ACTIVE, now, now, Optional.empty(), Optional.of(caseId), Optional.of("agent")));

        page.navigate(BASE_URL + "/app/session.html?id=sse-upd-w1&name=agent");

        // Wait for initial SSE render
        page.locator(".case-worker-row").first().waitFor(new Locator.WaitForOptions().setTimeout(1500));

        // Add a second worker to the case and push via SSE
        registry.register(new Session("sse-upd-w2", "claudony-worker-sse-upd-w2", "/tmp", "claude",
                SessionStatus.IDLE, now, now, Optional.empty(), Optional.of(caseId), Optional.of("coder")));
        broadcaster.emit(caseId);

        // Panel should update without waiting for any poll cycle
        page.locator(".case-worker-row").nth(1).waitFor(new Locator.WaitForOptions().setTimeout(2000));

        assertThat(page.locator(".case-worker-row").count()).isEqualTo(2);
        assertThat(page.locator(".case-worker-row").nth(1).textContent()).contains("coder");
    }

    // ── AC 7: closing panel closes EventSource ───────────────────────────────────

    @Test
    @Order(7)
    void casePanel_closingPanel_closesEventSource() {
        var now = Instant.now();
        var caseId = "e2e-sse-case-003";
        registry.register(new Session("sse-close-w1", "claudony-worker-sse-close-w1", "/tmp", "claude",
                SessionStatus.ACTIVE, now, now, Optional.empty(), Optional.of(caseId), Optional.of("planner")));

        page.addInitScript("window.__CLAUDONY_TEST_MODE__ = true;");
        page.navigate(BASE_URL + "/app/session.html?id=sse-close-w1&name=planner");

        // Wait for SSE connection to be established
        page.locator(".case-worker-row").first().waitFor(new Locator.WaitForOptions().setTimeout(1500));

        // Verify EventSource is open (readyState 1 = OPEN)
        var readyState = (Number) page.evaluate("() => window._caseEventSource ? window._caseEventSource.readyState : -1");
        assertThat(readyState.intValue()).isEqualTo(1);

        // Close the panel
        page.locator("#case-close-btn").click();
        page.waitForTimeout(200);

        // EventSource should be closed (readyState 2 = CLOSED)
        var closedState = (Number) page.evaluate("() => window._caseEventSource ? window._caseEventSource.readyState : 2");
        assertThat(closedState.intValue()).isEqualTo(2);
    }
}
