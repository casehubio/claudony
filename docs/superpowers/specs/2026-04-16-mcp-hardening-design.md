# MCP Hardening — Design Spec

**Goal:** Make Claudony's MCP layer robust against server failures, complete the test
coverage gaps identified in audit and Qhorus review, and harden integration test
assertions.

**Context:** See `docs/superpowers/2026-04-16-mcp-hardening-baseline.md` for full
history, feedback log, and decision rationale.

---

## Architecture

### Error handling in `ClaudonyMcpTools`

All 8 `@Tool` methods wrap their body in an identical two-tier catch. No changes to
method signatures or return types — tools return `String` throughout.

**Two private helpers on `ClaudonyMcpTools`:**

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

**Every `@Tool` method:**
```java
public String exampleTool(...) {
    try {
        // existing logic unchanged
    } catch (WebApplicationException e) { return serverError(e); }
      catch (Exception e)               { return connectError(e); }
}
```

**`openInTerminal` special case:** The existing `catch (IOException | InterruptedException e)`
for the adapter call sits *inside* the try block. The outer two-tier catch wraps the
whole method including the server calls that precede the adapter call.

**`getServerInfo` null guard:** Replace bare `config.serverUrl()` and `config.mode()` with
null-safe alternatives: `config.serverUrl() != null ? config.serverUrl() : "(not configured)"`.
Same for `config.mode()`.

### Timeout configuration

No code change. Two lines in `application.properties`:

```properties
quarkus.rest-client.claudony-server.connect-timeout=5000
quarkus.rest-client.claudony-server.read-timeout=10000
```

5 000 ms connect, 10 000 ms read. Read timeout is generous because `get_output` polls
a tmux pane that may be slow. The peer client has 3 000/2 000 ms; agent→server is
allowed longer because it's doing real work, not just a health check.

---

## Test Changes

### `ClaudonyMcpToolsTest` — 10 new tests

All use `@InjectMock @RestClient ServerClient serverClient` already in place.

**Error tier 1 — server responds with error (WebApplicationException):**

One test per tool verifying the relevant 4xx/5xx path. Minimum coverage:
- `listSessions_serverReturns500_returnsServerError` — mock throws `WebApplicationException` with status 500, assert return value starts with `"Server error"`
- `createSession_serverReturns409_returnsConflictMessage` — status 409, assert contains `"Conflict"`
- `deleteSession_serverReturns404_returnsNotFoundMessage` — status 404, assert contains `"list_sessions"` (the actionable hint)
- `renameSession_serverReturns404_returnsNotFoundMessage`
- `sendInput_serverReturns500_returnsServerError`
- `getOutput_serverReturns500_returnsServerError`
- `getServerInfo_serverReturns500_returnsServerError`

**Error tier 2 — server unreachable (general Exception):**

One test covering connect failure (representative — all tools use the same `connectError` helper):
- `listSessions_serverUnreachable_returnsConnectError` — mock throws `jakarta.ws.rs.ProcessingException("Connection refused")`, assert return starts with `"Unable to reach"`

**Missing `openInTerminal` paths:**

- `openInTerminal_sessionNotFound_returnsNotFoundMessage` — `terminalFactory.resolve()` returns present adapter, `serverClient.listSessions()` returns empty list, assert return equals `"Session not found."`
- `openInTerminal_adapterThrowsIOException_returnsError` — adapter present, session found in list, `adapter.openSession()` throws `IOException("pipe broken")`, assert return starts with `"Failed to open terminal"`
- `openInTerminal_sessionFound_opensAdapterAndReturnsAdapterName` — adapter present, session found, `openSession()` succeeds, assert return contains adapter name. This is the only happy path for `openInTerminal` not currently tested.

### `McpProtocolTest` — 3 new tests

Run these to discover actual library behaviour, then pin the exact values. Pattern follows
how the `400` status code was confirmed earlier.

- `toolsCall_withoutMcpSessionId_isRejected` — POST `tools/call` with correct Accept header but no `Mcp-Session-Id`, run test, observe status/error code, pin it
- `toolsCall_withInvalidSessionId_isRejected` — POST `tools/call` with `Mcp-Session-Id: not-a-real-id`, pin actual response
- `initialize_calledTwice_secondCallSucceeds` — call `initialize` twice on same `/mcp` endpoint, verify second response is 200 (or document actual behaviour if it differs)

### `McpServerIntegrationTest` — 2 fixes + 1 new test

**Fix — hard assertions instead of assumptions:**
Both occurrences of:
```java
Assumptions.assumeTrue(tmuxSessionId != null, "Could not extract session ID");
```
become:
```java
assertThat(tmuxSessionId).as("session ID extracted from create_session response").isNotNull();
```
Extraction failure now fails the test visibly instead of silently skipping it.

**Fix — robust session ID extraction:**
Replace:
```java
var parts = text.split("Browser: http://localhost:\\d+/app/session/");
return parts.length > 1 ? parts[1].trim() : null;
```
With:
```java
var m = java.util.regex.Pattern.compile("/app/session/([\\w-]+)").matcher(text);
return m.find() ? m.group(1) : null;
```
Captures the ID regardless of surrounding text format changes.

**New test — error scenario via full MCP chain:**
- `deleteSession_nonExistentId_returnsNotFoundMessage` — POST `delete_session` tool call with a made-up UUID that does not exist, assert tool response text contains `"not found"` (not an exception, not a JSON-RPC error — a readable string). This validates the error handling round-trip through the full MCP → REST → server stack.

---

## File Map

```
Modified:
  src/main/java/dev/claudony/agent/ClaudonyMcpTools.java
  src/main/resources/application.properties
  src/test/java/dev/claudony/agent/ClaudonyMcpToolsTest.java
  src/test/java/dev/claudony/agent/McpProtocolTest.java
  src/test/java/dev/claudony/agent/McpServerIntegrationTest.java
```

No new files. No other files touched.

---

## Acceptance Criteria

- All existing 214 tests continue to pass
- All new tests pass (expected total: ~227)
- `ClaudonyMcpTools` has no unhandled exceptions — every failure path returns a string
- `application.properties` has explicit connect and read timeouts for `claudony-server`
- `McpServerIntegrationTest` has zero `Assumptions.assumeTrue` calls
- The three new `McpProtocolTest` tests have pinned (not placeholder) assertions
