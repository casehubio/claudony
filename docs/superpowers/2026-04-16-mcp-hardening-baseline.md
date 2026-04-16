# MCP Hardening Baseline — 2026-04-16

Shared reference for the Claudony session, the Qhorus session, and Mark.
Captures every piece of feedback, every decision made, every item completed, and every
item still outstanding. Nothing should fall through the cracks.

---

## Background

The MCP layer in Claudony went through two major phases before this document was written:

**Phase 1 — Migration (Qhorus Claude, commit `6faf297`, issue #52)**
Replaced the hand-rolled `McpServer.java` JSON-RPC dispatcher with `quarkus-mcp-server-http`.
This was a prerequisite for the Qhorus Phase 8 embedding — Qhorus's `@Tool` beans need the
same MCP server extension on the classpath to auto-register alongside Claudony's 8 tools.

**Phase 2 — Test quality (Claudony Claude, commit `127bd27`, issue #53)**
Implemented the Qhorus Claude's test improvement recommendations (see §Qhorus Feedback below).

**Phase 3 — Reliability pass (Claudony Claude, issue #54)**
Seven-task systematic reliability and code quality pass. Not MCP-specific — covered the whole
codebase — but included test fixes that affected the MCP test files.

---

## Qhorus Claude Feedback (from `claudony-mcp-test-improvements.md`)

The Qhorus Claude reviewed the migration output and issued these recommendations.

| Recommendation | Status | Notes |
|---|---|---|
| `ClaudonyMcpToolsTest` — direct CDI calls, no HTTP | ✅ Done | 12 tests |
| `McpProtocolTest` — protocol compliance, pinned error codes | ✅ Done | 5 tests, -32601 confirmed |
| Delete `McpServerTest` | ✅ Done | Superseded by the two above |
| `McpServerIntegrationTest` — independent tests, `@BeforeEach` MCP session, `@AfterEach` cleanup | ✅ Done | Also improved with `Await.until()` instead of `Thread.sleep()` |
| Narrow `catch(Exception)` to declared types | ✅ Done | Issue #54, Task 7 |
| `openInTerminal` — only "no adapter" path tested | ❌ **Still missing** | Session-not-found and IOException paths not yet tested |

The Claudony Claude added `Await.until()` polling (instead of `Thread.sleep()`) as an
improvement beyond what was asked. The Qhorus Claude acknowledged this as the better approach.

---

## Claudony Claude Audit Findings (2026-04-16)

Full audit of the current MCP state. Items already done in issue #54 are noted.

### Done (issue #54, this session)

| Finding | Fix applied |
|---|---|
| Thread.sleep in test files (12 occurrences) | Replaced with `Await.until()` — Task 2 |
| Thread.sleep in TerminalWebSocketTest (14 occurrences) | Replaced with `awaitHistoryBurst()` + polling — Task 3 |
| `@TestMethodOrder`/`@Order` with no real ordering dependency | Removed from 5 test classes — Task 4 |
| `getResourceAsStream` unclosed in `AuthResource` | `try-with-resources` + narrow catch — Task 5 |
| Empty `catch (Exception ignored)` in `SessionResource` directory creation | Added `LOG.debugf(...)` — Task 6 |
| Missing error log in `SessionResource.rename()` | Added `LOG.errorf(...)` — Task 6 |
| Broad `catch(Exception)` across 5 production files | Narrowed to `IOException \| InterruptedException` — Task 7 |

### Outstanding — MCP-specific (the focus of the next phase)

See §TODO List below for the complete, prioritised list.

---

## Design Decisions Made

### Error handling in `ClaudonyMcpTools` — Option A approved

**Decision:** When a tool call fails, the `@Tool` method returns a human-readable error
string. The MCP protocol layer sees a successful tool result (no JSON-RPC error), and
Claude reads the content like any other tool output.

**Rationale (from Qhorus Claude):**
- Option B (`isError: true`) requires changing return types and knowing how the library
  exposes it from a `String`-returning `@Tool`. Adds complexity for no practical gain —
  Claude Code doesn't have special recovery logic for `isError: true` vs. error text.
- Option C (uncaught exception → JSON-RPC error) is the worst option. Unpredictable
  format that Claude can't reliably interpret.

**Architecture — Option 1 approved (two private helpers, per-method try/catch):**

```java
private String serverError(WebApplicationException e) {
    return switch (e.getResponse().getStatus()) {
        case 404 -> "Session not found. Use list_sessions to see available sessions.";
        case 409 -> "Conflict: a session with that name already exists.";
        case 401, 403 -> "Authentication error. Check that the agent API key is configured correctly.";
        default  -> "Server error (HTTP %d). Check that the Claudony server is running."
                        .formatted(e.getResponse().getStatus());
    };
}

private String connectError(Exception e) {
    return "Unable to reach Claudony server — " + e.getMessage()
           + ". Check server URL and that the server is running.";
}
```

Every `@Tool` method wraps its body:
```java
} catch (WebApplicationException e) { return serverError(e); }
  catch (Exception e)               { return connectError(e); }
```

Option 2 (functional `call(Supplier<String>)` wrapper) was considered but rejected as
adding unnecessary indirection. Option 3 (CDI interceptor) was rejected as overengineered.

### Timeouts — config only, no code

```properties
quarkus.rest-client.claudony-server.connect-timeout=5000
quarkus.rest-client.claudony-server.read-timeout=10000
```

MicroProfile Rest Client honours these directly. No provider or annotation needed.
(The peer client already has explicit timeouts at 3s/2s — agent client was missing them.)

---

## TODO List — Complete and Prioritised

Grouped by file. Everything here is agreed by all three parties.

### Priority 1 — Error handling (production code)

These are coupled: implement first, then write the tests.

**`ClaudonyMcpTools.java`**
- [ ] Add `private String serverError(WebApplicationException e)` helper (status-specific messages: 404, 409, 401/403, default)
- [ ] Add `private String connectError(Exception e)` helper
- [ ] Wrap `listSessions()` in two-tier try/catch
- [ ] Wrap `createSession()` in two-tier try/catch
- [ ] Wrap `deleteSession()` in two-tier try/catch
- [ ] Wrap `renameSession()` in two-tier try/catch
- [ ] Wrap `sendInput()` in two-tier try/catch
- [ ] Wrap `getOutput()` in two-tier try/catch
- [ ] Wrap `openInTerminal()` outer body in two-tier try/catch (inner IOException catch for adapter is already correct — keep it inside)
- [ ] Wrap `getServerInfo()` in two-tier try/catch
- [ ] `getServerInfo()` null-safety: guard `config.serverUrl()` and `config.mode()` against null

**`application.properties`**
- [ ] Add `quarkus.rest-client.claudony-server.connect-timeout=5000`
- [ ] Add `quarkus.rest-client.claudony-server.read-timeout=10000`

### Priority 2 — Error scenario tests (unit)

Only write these after Priority 1 is implemented.

**`ClaudonyMcpToolsTest.java`**
- [ ] `listSessions_serverReturns500_returnsServerError` — mock throws `WebApplicationException(500)`, assert return starts with "Server error"
- [ ] `listSessions_serverUnreachable_returnsConnectError` — mock throws `ProcessingException`, assert return starts with "Unable to reach"
- [ ] `createSession_serverReturns409_returnsConflictMessage` — mock throws `WebApplicationException(409)`
- [ ] `deleteSession_serverReturns404_returnsNotFoundMessage` — mock throws `WebApplicationException(404)`, assert message mentions "list_sessions"
- [ ] `renameSession_serverError_returnsError`
- [ ] `sendInput_serverError_returnsError`
- [ ] `getOutput_serverError_returnsError`
- [ ] `openInTerminal_sessionNotFound_returnsNotFoundMessage` — adapter present, `server.listSessions()` returns empty list
- [ ] `openInTerminal_adapterThrowsIOException_returnsErrorMessage` — adapter present, session found, `openSession()` throws `IOException`
- [ ] `getServerInfo_serverError_returnsError`

### Priority 3 — Integration test hardening

**`McpServerIntegrationTest.java`**
- [ ] Replace `Assumptions.assumeTrue(tmuxSessionId != null, ...)` with `assertThat(tmuxSessionId).as(...).isNotNull()` (currently hides failures as skips)
- [ ] Fix fragile session ID extraction: replace `text.split("Browser: http://localhost:\\d+/app/session/")` with `Pattern.compile(".*/app/session/([\\w-]+)").matcher(text)` and capture group 1
- [ ] Add error scenario integration test: `deleteSession_nonExistentId_returnsNotFoundMessage` — call delete with made-up UUID, assert tool returns the 404 message (not an exception)

### Priority 4 — Protocol compliance tests

**`McpProtocolTest.java`**
- [ ] `toolsCall_withoutMcpSessionId_isRejected` — POST `tools/call` with valid Accept header but no `Mcp-Session-Id`, verify error response (determine exact error code/status from actual run)
- [ ] `toolsCall_withInvalidSessionId_isRejected` — POST `tools/call` with `Mcp-Session-Id: not-a-real-id`, verify error response
- [ ] `initialize_calledTwice_behavesCorrectly` — call `initialize` twice, verify second call succeeds or returns appropriate error (document actual library behaviour)

### Priority 5 — Minor gaps (low effort, good to have)

**`ClaudonyMcpToolsTest.java`**
- [ ] `openInTerminal_sessionFound_opensAdapter` — adapter present, `server.listSessions()` returns session with matching ID, `openSession()` succeeds, assert return contains adapter name (the only happy path for `openInTerminal` not yet covered)

**`ClaudonyMcpTools.java` (optional)**
- [ ] Consider adding `LOG.debugf(...)` calls at tool entry for debugging: `LOG.debugf("list_sessions called")`. Low priority — not blocking anything.

---

## What Each Party Owns Next

**Claudony Claude (this session):**
Write the implementation plan (via `writing-plans` skill) and execute it.
Covers all items in the TODO list above.

**Qhorus Claude:**
Continue Phase 8 embedding work once the MCP hardening is complete and Claudony's
`quarkus-mcp-server-http` is confirmed stable. At that point:
- Add `quarkus-qhorus` + `quarkus-qhorus-deployment` to Claudony's `pom.xml`
- Add datasource (H2 for dev/test), Flyway picks up Qhorus V1–V8 migrations
- Add `quarkus.qhorus.enabled=true` to `application.properties`
- Verify 38 Qhorus tools auto-register alongside Claudony's 8 at the `/mcp` endpoint

**Mark:**
Review this doc, confirm nothing is missing, then kick off the `writing-plans` phase.

---

## Current Test Counts

- **214 tests passing** as of commit `02485f4`
- After MCP hardening: expect ~224+ (10+ new error scenario and protocol tests)
- All new tests must pass before Qhorus embedding begins

---

## Key Files

```
src/main/java/dev/claudony/agent/
├── ClaudonyMcpTools.java          — 8 @Tool methods, needs error handling
└── ServerClient.java              — REST client, needs timeout config in properties

src/test/java/dev/claudony/agent/
├── ClaudonyMcpToolsTest.java      — 12 unit tests (direct CDI), needs error scenarios
├── McpProtocolTest.java           — 5 protocol tests, needs 3 more
└── McpServerIntegrationTest.java  — 5 integration tests, needs assertion hardening

src/main/resources/application.properties  — needs connect/read timeout for claudony-server
```

---

## Commits Covering This Work

| Commit | Description | Issue |
|---|---|---|
| `6faf297` | Migrate McpServer.java → quarkus-mcp-server-http | #52 |
| `127bd27` | Split tests by concern (tool logic vs protocol) | #53 |
| `09e0486`–`02485f4` | Reliability pass (7 tasks) | #54 |
| _(next)_ | MCP hardening — error handling + timeout + tests | #55 (TBD) |
