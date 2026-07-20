package io.casehub.claudony.e2e;

import com.microsoft.playwright.Locator;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class WorkbenchE2ETest extends PlaywrightBase {

    @Inject
    SessionRegistry registry;

    @AfterEach
    void cleanupSessions() {
        registry.all().stream().map(Session::id).toList().forEach(registry::remove);
    }

    @Test
    void caseBoundSession_rendersWorkbench() {
        var now = Instant.now();
        var caseId = UUID.randomUUID().toString();
        registry.register(new Session("e2e-wb-case", "claudony-e2e-wb", "/tmp", "claude",
                SessionStatus.IDLE, now, now,
                Optional.of(caseId), Optional.of("analyst"), Optional.empty(),
                TenancyConstants.DEFAULT_TENANT_ID));

        page.addInitScript("window.__CLAUDONY_TEST_MODE__ = true;");
        page.navigate(BASE_URL + "/app/session.html?id=e2e-wb-case&name=e2e-workbench");

        page.locator("claudony-workbench").waitFor(
                new Locator.WaitForOptions().setTimeout(10000));
        assertThat(page.locator("claudony-workbench").isVisible())
                .as("Workbench should render for case-bound session")
                .isTrue();

        assertThat(page.locator("claudony-terminal-workspace").count())
                .as("terminal-workspace should NOT be present when workbench renders")
                .isZero();
    }

    @Test
    void standaloneSession_rendersFleetMode() {
        var now = Instant.now();
        registry.register(new Session("e2e-wb-fleet", "claudony-e2e-fleet", "/tmp", "claude",
                SessionStatus.IDLE, now, now,
                Optional.empty(), Optional.empty(), Optional.empty(),
                TenancyConstants.DEFAULT_TENANT_ID));

        page.navigate(BASE_URL + "/app/session.html?id=e2e-wb-fleet&name=e2e-fleet");

        page.locator("claudony-terminal-workspace").waitFor(
                new Locator.WaitForOptions().setTimeout(10000));
        assertThat(page.locator("claudony-terminal-workspace").isVisible())
                .as("terminal-workspace should render for standalone session")
                .isTrue();

        assertThat(page.locator("claudony-workbench").count())
                .as("Workbench should NOT be present for standalone session")
                .isZero();
    }

    @Test
    void workbench_hasTerminalContainer() {
        var now = Instant.now();
        var caseId = UUID.randomUUID().toString();
        registry.register(new Session("e2e-wb-term", "claudony-e2e-term", "/tmp", "claude",
                SessionStatus.IDLE, now, now,
                Optional.of(caseId), Optional.of("coder"), Optional.empty(),
                TenancyConstants.DEFAULT_TENANT_ID));

        page.addInitScript("window.__CLAUDONY_TEST_MODE__ = true;");
        page.navigate(BASE_URL + "/app/session.html?id=e2e-wb-term&name=e2e-term");

        page.locator("claudony-workbench").waitFor(
                new Locator.WaitForOptions().setTimeout(10000));

        var termContainer = page.locator("claudony-workbench #terminal-container");
        assertThat(termContainer.count())
                .as("Workbench should have a terminal container")
                .isGreaterThan(0);
    }

    @Test
    void workbench_hasDockStrip() {
        var now = Instant.now();
        var caseId = UUID.randomUUID().toString();
        registry.register(new Session("e2e-wb-dock", "claudony-e2e-dock", "/tmp", "claude",
                SessionStatus.IDLE, now, now,
                Optional.of(caseId), Optional.of("reviewer"), Optional.empty(),
                TenancyConstants.DEFAULT_TENANT_ID));

        page.addInitScript("window.__CLAUDONY_TEST_MODE__ = true;");
        page.navigate(BASE_URL + "/app/session.html?id=e2e-wb-dock&name=e2e-dock");

        page.locator("claudony-workbench").waitFor(
                new Locator.WaitForOptions().setTimeout(10000));

        // Dock strip buttons for Tasks, Correlation, Artifacts should exist in shadow DOM
        // Use evaluate to pierce shadow root
        var dockButtons = page.evaluate(
                "() => { const wb = document.querySelector('claudony-workbench'); " +
                "if (!wb || !wb.shadowRoot) return 0; " +
                "return wb.shadowRoot.querySelectorAll('.dock-btn').length; }");
        assertThat(((Number) dockButtons).intValue())
                .as("Dock strip should have buttons")
                .isGreaterThanOrEqualTo(3);
    }
}
