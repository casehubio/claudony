# Design: Playwright E2E Browser Testing

**Date:** 2026-04-15
**Status:** Approved
**Feature:** Playwright for Java — browser-based E2E tests for the dashboard and terminal UI

---

## Problem

Claudony's dashboard and terminal UI have no automated browser testing. As the UI grows more complex (fleet panel, session cards, PROXY mode, terminal streaming), regressions are increasingly likely. `StaticFilesTest` only verifies files exist; it cannot test interactive behaviour, DOM state, or JavaScript logic.

---

## Solution

Add Playwright for Java as a test-scope Maven dependency. Browser tests live in `src/test/java/dev/claudony/e2e/`, use the existing `-Pe2e` Maven profile, and run via `@QuarkusTest` so the server starts before the browser connects. No Node.js required.

---

## Architecture

### Maven dependency

```xml
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
    <version>1.52.0</version>
    <scope>test</scope>
</dependency>
```

Playwright for Java bundles Chromium (downloaded on first use). No separate browser installation step unless running in CI without internet access.

**One-time browser install command** (required before first run):
```bash
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

Document in CLAUDE.md under "Build and Test".

### Test naming

Named `*E2ETest.java` so they are automatically included by the existing `-Pe2e` Maven profile (which already includes `**/*E2ETest.java`). No new Maven profile needed.

Run command (unchanged from existing e2e tests):
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Pe2e
```

To run only browser tests:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Pe2e -Dtest=DashboardE2ETest,TerminalPageE2ETest
```

### PlaywrightBase (abstract base class)

```
src/test/java/dev/claudony/e2e/PlaywrightBase.java
```

Manages `Playwright`, `Browser`, `BrowserContext`, and `Page` lifecycle:

- `@BeforeAll` — creates `Playwright` instance and launches `Browser` (Chromium)
- `@BeforeEach` — creates a fresh `BrowserContext` and `Page`; injects auth header and base URL
- `@AfterEach` — closes `BrowserContext`
- `@AfterAll` — closes `Browser` and `Playwright`

**Auth:** `page.setExtraHTTPHeaders(Map.of("X-Api-Key", "test-api-key-do-not-use-in-prod"))` injects the test API key into every request the page makes — page HTML load, all `fetch()` calls in `dashboard.js`. The test profile already sets `%test.claudony.agent.api-key=test-api-key-do-not-use-in-prod`. This handles all dashboard REST calls.

WebSocket connections (used by `terminal.js`) do not inherit extra headers from `setExtraHTTPHeaders`. Terminal WebSocket auth is deferred to the "Expand E2E coverage" epic.

**Headless/headed:**
```java
var headless = !Boolean.FALSE.equals(
    Boolean.valueOf(System.getProperty("playwright.headless", "true")));
browser = playwright.chromium().launch(
    new BrowserType.LaunchOptions().setHeadless(headless));
```

Default: headless. Debug locally with `-Dplaywright.headless=false`.

**Base URL:** `http://localhost:8081` (Quarkus test port).

---

## Test Classes

### `PlaywrightSetupE2ETest` — `src/test/java/dev/claudony/e2e/PlaywrightSetupE2ETest.java`

4 tests that verify the test infrastructure itself before any real tests run. These should be the first tests to pass on a new machine or CI environment. A failure here means "the architecture is broken" — fix the setup before running functional tests.

| Test | What it asserts |
|---|---|
| `playwright_chromiumLaunches` | `browser.isConnected()` is true — Chromium started successfully |
| `playwright_canNavigate` | `page.navigate(BASE_URL + "/app/")` returns HTTP 200 — server is reachable |
| `authHeader_allowsProtectedEndpoint` | `page.navigate(BASE_URL + "/api/sessions")` returns HTTP 200 — `setExtraHTTPHeaders` is injecting the API key and the server accepts it |
| `unauthenticated_context_isBlocked` | A fresh `BrowserContext` created without any extra headers navigates to `/api/sessions` and gets HTTP 401 — confirming that the auth protection is active and the test context is the thing providing access, not a misconfiguration |

The unauthenticated test creates a temporary `BrowserContext` directly from `browser` (not via `PlaywrightBase`'s `@BeforeEach` which sets the headers) so it can test the no-auth case without affecting other tests.

---

### `DashboardE2ETest` — `src/test/java/dev/claudony/e2e/DashboardE2ETest.java`

7 tests covering the dashboard golden path. Each test is isolated — no state bleeds between tests because `BrowserContext` is fresh per test.

| Test | What it asserts |
|---|---|
| `pageTitle_isClaudony` | `<title>` text equals "Claudony" |
| `fleetPanel_visible_withNoPeersMessage` | `#fleet-panel` is visible; contains text "No peers configured" |
| `sessionGrid_showsEmptyState_withNoSessions` | Grid contains "No active sessions" text |
| `newSessionDialog_opensAndCloses` | Click `#new-session-btn` → dialog visible; click Cancel → dialog closed |
| `addPeerDialog_opensAndCloses` | Click `#add-peer-btn` → dialog with URL/Name/Mode fields; Cancel closes it |
| `sessionCard_appearsAfterApiCreate` | POST `/api/sessions` via Playwright `APIRequestContext`, wait for card to appear in grid, verify name and status badge |
| `unauthenticated_redirectsToLogin` | Navigate without API key header → redirect to `/auth/login` |

### `TerminalPageE2ETest` — `src/test/java/dev/claudony/e2e/TerminalPageE2ETest.java`

1 test. Terminal WebSocket auth is deferred — this test only verifies page structure loads.

| Test | What it asserts |
|---|---|
| `terminalPage_loadsStructure` | Navigate to `/app/session.html?id=fake-id&name=test` → `#terminal-container` present in DOM; status badge shows "reconnecting" (WebSocket fails, page degrades gracefully) |

---

## Explicitly Out of Scope (deferred to "Expand E2E coverage" epic)

- Terminal I/O testing (requires live tmux session in test environment)
- Fleet peer interaction (requires second Claudony instance, likely Docker)
- PROXY resize verification (deferred until resize is fixed, then added to its own issue)
- WebAuthn passkey flow (complex browser automation for platform authenticators)
- Mobile/iPad viewport behaviour

---

## Files Changed

| File | Action |
|---|---|
| `pom.xml` | Add `playwright` dependency (test scope) |
| `src/test/java/dev/claudony/e2e/PlaywrightBase.java` | **Create** — abstract base class |
| `src/test/java/dev/claudony/e2e/PlaywrightSetupE2ETest.java` | **Create** — 4 architecture verification tests |
| `src/test/java/dev/claudony/e2e/DashboardE2ETest.java` | **Create** — 7 dashboard tests |
| `src/test/java/dev/claudony/e2e/TerminalPageE2ETest.java` | **Create** — 1 terminal page structure test |
| `CLAUDE.md` | Add Playwright browser install command under Build and Test |

---

## Running the Tests

```bash
# One-time: install Chromium (first time on each machine)
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"

# Run all e2e tests (Claude CLI + browser)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Pe2e

# Run only browser tests
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Pe2e -Dtest=DashboardE2ETest,TerminalPageE2ETest

# Run with visible browser (local debugging)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Pe2e -Dplaywright.headless=false -Dtest=DashboardE2ETest
```

---

## CI Notes

In CI environments without a display, Chromium runs headless (the default). No `xvfb` or virtual display needed. Playwright's bundled Chromium is self-contained.

If the CI environment has no internet access, the Chromium download step must be cached. The browser is stored at `~/.cache/ms-playwright/` by default.
