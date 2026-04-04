# RemoteCC — End-to-End Testing & Hardening Design

**Date:** 2026-04-05
**Status:** Approved
**Scope:** Bug fix + comprehensive test coverage from unit through real Claude E2E

---

## Context

Plans 1–3 are complete. The system runs: 64 tests passing, native binary works,
MCP server exposes 8 tools. One bug was found during this design session. One
critical gap: the controller Claude end-to-end flow (MCP → sessions) has never
been exercised with a real Claude process.

This spec covers:
1. Fixing a discovered bug in the REST input path
2. Filling integration test gaps across existing test classes
3. Expanding the scripted MCP protocol test suite
4. Adding real Claude E2E smoke tests behind a Maven profile
5. CI/CD strategy that keeps the default build fast and API-key-free

---

## Bug Fix: TmuxService.sendKeys Missing `-l` Flag

`TmuxService.sendKeys()` (used by `POST /api/sessions/{id}/input`) invokes
`tmux send-keys` without the `-l` (literal) flag:

```java
// Current — WRONG: tmux interprets "Enter", "Escape" etc. as key names
new ProcessBuilder("tmux", "send-keys", "-t", sessionName, text, "")

// Fix — CORRECT: literal mode, raw bytes
new ProcessBuilder("tmux", "send-keys", "-t", sessionName, "-l", text)
```

`TerminalWebSocket` already uses `-l` correctly. This creates an inconsistency:
WebSocket input and REST input behave differently for text containing tmux key
names. The bug is silent and intermittent — only triggers when input happens to
contain a word like "Escape", "Enter", "Space", etc.

**Tests required (both levels):**
- `TmuxServiceTest`: call `sendKeys()` with text containing a tmux key name
  (e.g. `"echo Escape marker"`), assert `capturePane` shows the literal text
- `SessionInputOutputTest`: send the same text via `POST /api/sessions/{id}/input`,
  assert output contains the literal text (proves the fix is wired through REST)

---

## Layer 1: Unit Test Gaps (McpServerTest — mocked)

Existing: 5 tests covering initialize, tools/list, list_sessions, create_session,
unknown method.

**Add:**
- `create_session` with missing `name` argument → graceful error response, not 500
- `send_input` with text containing tmux key name literal (validates `-l` wiring
  through MCP → REST, complementing the direct service test)
- `rename_session` tool → proxies to server, returns new name
- `open_in_terminal` with no adapter → returns "No terminal adapter available"
- `get_server_info` → returns Server URL, mode, adapter fields

---

## Layer 2: Integration Test Gaps (real tmux, in-process Quarkus)

### SessionResourceTest
- `PATCH /api/sessions/{id}/rename` → returns 200 with new name, registry updated
- `POST /api/sessions/{id}/resize` → returns 204, tmux pane resized

### SessionInputOutputTest
- `sendKeys` with tmux key name via REST → literal text appears in output
  (the bug-fix integration test)

### McpServerIntegrationTest (real ServerClient, no mocks)
Current: Orders 1–7, happy path create/list/send/get/delete.

**Add:**
- `tools/list` → returns 8 tools with correct names (integration version)
- `rename_session` → full MCP → REST → tmux chain, new name in response
- `open_in_terminal` (no adapter) → clean "No terminal adapter" message, not error
- Full lifecycle sequence: create → rename → send input with tmux key name →
  get output (assert literal text present) → delete

### TerminalWebSocketTest
- **History replay**: connect, send command, disconnect, reconnect, assert command
  appears in history. This is the most important user-facing behaviour without
  any current test.
- **Concurrent connections**: two WebSocket clients connect to the same session
  simultaneously. Both should receive output (pipe-pane allows one active pipe —
  verify behaviour is defined and doesn't crash).

### ServerStartupTest
- Pre-create a tmux session with `remotecc-` prefix, clear the registry, run
  bootstrap, assert session is found in registry with correct name.

---

## Layer 3: Scripted MCP Protocol Tests (McpServerIntegrationTest additions)

These test the exact JSON-RPC sequences a real Claude client would send,
providing deterministic CI coverage of the MCP protocol layer.

**Sequences to add:**
- `initialize` → `notifications/initialized` → `tools/list` (the full handshake
  as Claude performs it, not just individual calls)
- `tools/call` with unknown tool name → JSON-RPC error response (code -32602),
  not HTTP 500
- `tools/call` with `create_session` missing required `name` → graceful error
- Complete ordered lifecycle: create → rename → send (tmux key name) → get
  output → delete, asserting each step

---

## Layer 4: Real Claude E2E Tests (ClaudeE2ETest, `-Pe2e` profile)

### Approach

The Quarkus test instance runs on port 8081. The Agent's `ServerClient` is
already configured to loop back to port 8081 in test mode. The full MCP
endpoint at `http://localhost:8081/mcp` is functional during tests.

`claude` CLI is invoked with a temporary MCP config file pointing at that
endpoint:

```json
{
  "mcpServers": {
    "remotecc": {
      "type": "http",
      "url": "http://localhost:8081/mcp"
    }
  }
}
```

```java
// Write temp config, invoke claude, assert on tmux state
var mcpConfig = Files.createTempFile("remotecc-e2e-mcp", ".json");
Files.writeString(mcpConfig, """
    {"mcpServers":{"remotecc":{"type":"http","url":"http://localhost:8081/mcp"}}}
    """);

var pb = new ProcessBuilder(
    "claude", "--mcp-config", mcpConfig.toString(),
    "--dangerously-skip-permissions", "-p", prompt)
    .redirectErrorStream(true);
pb.environment().put("ANTHROPIC_API_KEY", System.getenv("ANTHROPIC_API_KEY"));
var process = pb.start();
assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Claude timed out");
```

**Assertions are on side effects only** — not on Claude's text output:
```java
// After "create a session called e2e-test in /tmp":
var exists = new ProcessBuilder("tmux", "has-session", "-t", "remotecc-e2e-test")
    .start().waitFor() == 0;
assertTrue(exists, "tmux session should exist after Claude created it");
```

### Tests (2 only)

**Test 1: Tool discovery smoke test**
- Prompt: `"What tools do you have from the remotecc MCP server? List them."`
- Assert: process exits 0 (Claude connected, got tool list, responded without
  error). No text assertion.

**Test 2: Full lifecycle**
- Prompt: `"Create a RemoteCC session called e2e-test in /tmp, then send it the
  command 'echo remotecc-e2e-marker' and confirm it ran."`
- Assert after create: `tmux has-session -t remotecc-e2e-test` exits 0
- Assert after send: `tmux capture-pane -t remotecc-e2e-test -p` contains
  `remotecc-e2e-marker`
- Cleanup: delete the session via REST regardless of test outcome

### Maven profile

```xml
<profile>
  <id>e2e</id>
  <build>
    <plugins>
      <plugin>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <includes>
            <include>**/*E2ETest.java</include>
          </includes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

`ClaudeE2ETest` uses `@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY",
matches = ".+")` so it skips gracefully if the key is absent rather than failing.

The default surefire configuration excludes `**/*E2ETest.java` so E2E tests
never run in `mvn test`.

---

## CI/CD Strategy

| Command | When | What runs |
|---|---|---|
| `mvn test` | Every commit, CI gate | All 64 existing + all new unit + integration tests. No API key needed. |
| `mvn test -Pe2e` | Developer, pre-release, MCP interface changes | + `ClaudeE2ETest`. Requires `ANTHROPIC_API_KEY`. |
| `mvn package -Pnative -DskipTests` | As needed | Native binary build. Unchanged. |

**When to re-run `-Pe2e`:**
- Adding a new MCP tool
- Changing a tool's description (Claude uses descriptions to decide when to call)
- Changing required argument names or types
- Pre-release validation

**The contract:** The scripted `McpServerIntegrationTest` sequences ARE what Claude
sends. If those pass, the real Claude will work (assuming the MCP interface hasn't
changed). The E2E tests prove the interface is correct; the integration tests prove
it stays correct across commits.

---

## Test Count Delta

| Class | Existing | New | Total |
|---|---|---|---|
| `McpServerTest` | 5 | 5 | 10 |
| `McpServerIntegrationTest` | 7 | 6 | 13 |
| `SessionResourceTest` | 5 | 2 | 7 |
| `SessionInputOutputTest` | 4 | 1 | 5 |
| `TmuxServiceTest` | 5 | 1 | 6 |
| `TerminalWebSocketTest` | 2 | 2 | 4 |
| `ServerStartupTest` | existing | 1 | +1 |
| `ClaudeE2ETest` | 0 | 2 | 2 |
| **Total** | **64** | **~20** | **~84** |

---

## Files Changed

| File | Change |
|---|---|
| `TmuxService.java` | Fix `-l` flag in `sendKeys()` |
| `TmuxServiceTest.java` | Add `sendKeys` literal text test |
| `SessionInputOutputTest.java` | Add tmux key name round-trip test |
| `SessionResourceTest.java` | Add rename + resize tests |
| `McpServerTest.java` | Add 5 unit tests |
| `McpServerIntegrationTest.java` | Add 6 integration + protocol tests |
| `TerminalWebSocketTest.java` | Add history replay + concurrent connection tests |
| `ServerStartupTest.java` | Add bootstrap-from-tmux test |
| `ClaudeE2ETest.java` | New — 2 E2E tests |
| `pom.xml` | Add `-Pe2e` profile, exclude `*E2ETest` from default surefire |
