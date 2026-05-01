package dev.claudony.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.WaitForSelectorState;
import dev.claudony.server.SessionRegistry;
import dev.claudony.server.model.Session;
import dev.claudony.server.model.SessionStatus;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.testing.InMemoryChannelStore;
import io.casehub.qhorus.testing.InMemoryMessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for the case context section of the channel panel.
 *
 * <p>Covers: case header visible when caseId is present, absent when not,
 * lineage toggle expands/collapses, channel auto-selects to case-{caseId}/work.
 *
 * <p>Lineage always shows "0 prior workers" — EmptyCaseLineageQuery is the active bean.
 */
@QuarkusTest
class CaseContextPanelE2ETest extends PlaywrightBase {

    @Inject SessionRegistry registry;
    @Inject QhorusMcpTools tools;
    @Inject InMemoryChannelStore channelStore;
    @Inject InMemoryMessageStore messageStore;

    private static final String CASE_ID = "550e8400-e29b-41d4-a716-446655440001";

    @BeforeEach
    void setup() {
        var now = Instant.now();
        // CaseHub session — has caseId and roleName
        registry.register(new Session("ctx-case-session", "claudony-ctx-case", "/tmp", "claude",
                SessionStatus.ACTIVE, now.minusSeconds(600), now, Optional.empty(),
                Optional.of(CASE_ID), Optional.of("researcher")));

        // Standalone session — no caseId
        registry.register(new Session("ctx-standalone", "claudony-ctx-standalone", "/tmp", "claude",
                SessionStatus.IDLE, now, now, Optional.empty(),
                Optional.empty(), Optional.empty()));
    }

    @AfterEach
    void cleanup() {
        registry.all().stream().map(Session::id).toList().forEach(registry::remove);
        messageStore.clear();
        channelStore.clear();
    }

    private void openChannelPanel() {
        page.locator("#ch-toggle-btn").click();
        page.locator("#channel-panel:not(.collapsed)").waitFor(
                new Locator.WaitForOptions().setTimeout(5000));
    }

    // ── AC 1: case header visible when session has caseId ────────────────────

    @Test
    void caseSession_showsCaseHeaderWithRoleAndStatus() {
        page.navigate(BASE_URL + "/app/session.html?id=ctx-case-session&name=researcher");
        openChannelPanel();

        // Case header must be present
        page.locator(".ch-case-header").waitFor(new Locator.WaitForOptions().setTimeout(4000));
        assertThat(page.locator(".ch-case-header").count()).isEqualTo(1);

        // Role name must appear
        assertThat(page.locator(".ch-case-role").textContent()).isEqualTo("researcher");

        // Status dot must have the 'active' class (session status = ACTIVE)
        assertThat(page.locator(".ch-case-header .worker-status-dot").getAttribute("class"))
                .contains("active");

        // Elapsed display must be present (non-empty — session is 10 minutes old)
        var elapsed = page.locator(".ch-case-elapsed").textContent();
        assertThat(elapsed).isNotBlank();
    }

    // ── AC 2: no case header for standalone session ───────────────────────────

    @Test
    void standaloneSession_noCaseHeader() {
        page.navigate(BASE_URL + "/app/session.html?id=ctx-standalone&name=standalone");
        openChannelPanel();

        // Give JS time to settle
        page.waitForTimeout(1000);

        assertThat(page.locator(".ch-case-header").count())
                .as("No case header for standalone session")
                .isEqualTo(0);
    }

    // ── AC 3: lineage toggle expands and collapses ────────────────────────────

    @Test
    void lineageToggle_expandsAndCollapses() {
        page.navigate(BASE_URL + "/app/session.html?id=ctx-case-session&name=researcher");
        openChannelPanel();

        page.locator(".ch-lineage-toggle").waitFor(new Locator.WaitForOptions().setTimeout(4000));

        // Lineage section starts hidden
        assertThat(page.locator(".ch-lineage").getAttribute("class"))
                .contains("ch-lineage-hidden");

        // Click toggle → expands
        page.locator(".ch-lineage-toggle").click();
        assertThat(page.locator(".ch-lineage").getAttribute("class"))
                .doesNotContain("ch-lineage-hidden");

        // Toggle row shows "0 prior workers" (EmptyCaseLineageQuery)
        assertThat(page.locator(".ch-lineage-count").textContent())
                .isEqualTo("0 prior workers");

        // Click again → collapses
        page.locator(".ch-lineage-toggle").click();
        assertThat(page.locator(".ch-lineage").getAttribute("class"))
                .contains("ch-lineage-hidden");
    }

    // ── AC 4: channel auto-selects to case-{caseId}/work ─────────────────────

    @Test
    void caseSession_autoSelectsCaseChannel() {
        var caseChannelName = "case-" + CASE_ID + "/work";
        tools.createChannel(caseChannelName, "Case work channel", "APPEND", null, null, null, null, null, null);

        page.navigate(BASE_URL + "/app/session.html?id=ctx-case-session&name=researcher");
        openChannelPanel();

        // Wait for the case channel to be auto-selected in the dropdown
        // <option> elements are never "visible" in Playwright — use ATTACHED
        page.locator("#ch-select option[value='" + caseChannelName + "']").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(5000));

        var selectedValue = page.evaluate("document.getElementById('ch-select').value");
        assertThat(selectedValue.toString()).isEqualTo(caseChannelName);
    }
}
