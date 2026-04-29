package dev.claudony.e2e;

import dev.claudony.server.SessionRegistry;
import dev.claudony.server.model.Session;
import dev.claudony.server.model.SessionStatus;
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
 * and clicking a worker row navigates to that session and updates the highlight.
 *
 * <p>Auth: page requests include the test API key via PlaywrightBase.setExtraHTTPHeaders.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CaseWorkerPanelE2ETest extends PlaywrightBase {

    @Inject
    SessionRegistry registry;

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
                Optional.empty(), Optional.of(caseId), Optional.of("researcher")));
        registry.register(new Session("e2e-w2", "claudony-worker-w2", "/tmp", "claude",
                SessionStatus.IDLE, now, now,
                Optional.empty(), Optional.of(caseId), Optional.of("coder")));

        page.navigate(BASE_URL + "/app/session.html?id=e2e-w1&name=researcher");
        page.waitForTimeout(1500);

        assertThat(page.locator("#case-panel").getAttribute("class"))
                .as("Panel should auto-expand for CaseHub session")
                .doesNotContain("collapsed");

        var rows = page.locator(".case-worker-row");
        assertThat(rows.count())
                .as("Both workers should be listed")
                .isEqualTo(2);

        assertThat(rows.nth(0).getAttribute("class"))
                .as("First worker (researcher) should be highlighted as active")
                .contains("active-worker");
        assertThat(rows.nth(0).textContent())
                .as("First row should show researcher role")
                .contains("researcher");

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
        page.waitForTimeout(1500);

        // Click the second worker row (executor)
        page.locator(".case-worker-row").nth(1).click();
        page.waitForTimeout(600);

        assertThat(page.url())
                .as("URL should update to executor session")
                .contains("e2e-c2");

        // Wait for next poll cycle to re-render highlights
        page.waitForTimeout(1500);

        assertThat(page.locator(".case-worker-row").nth(0).getAttribute("class"))
                .as("Planner should no longer be highlighted")
                .doesNotContain("active-worker");
        assertThat(page.locator(".case-worker-row").nth(1).getAttribute("class"))
                .as("Executor should now be highlighted")
                .contains("active-worker");
    }
}
