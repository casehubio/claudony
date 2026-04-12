# Claudony — Wiring the Control Plane

**Date:** 2026-04-04
**Type:** phase-update

---

## What We Were Trying To Achieve

Plan 2 was the Agent: the lightweight Quarkus mode that exposes MCP tools
for the controller Claude and handles local machine automation. The goal was
for a Claude Code instance — the controller — to be able to call
`list_sessions`, `create_session`, `send_input`, `open_in_terminal` and
have them just work. The controller Claude should be able to manage your
entire Claude session fleet through conversation.

This phase also added two REST endpoints to the Server that the Agent would
proxy — `POST /api/sessions/{id}/input` (send keystrokes to a session) and
`GET /api/sessions/{id}/output` (capture recent terminal output). These
hadn't been in Plan 1 because they're only needed via the MCP layer.

## What We Believed Going In

The MCP server piece felt like the most interesting part and also the most
novel — there aren't many examples of hand-rolled JSON-RPC MCP servers in
Quarkus. We expected the implementation to be mechanical but the protocol
itself to be fiddly.

The `ServerClient` — the typed REST client the Agent uses to call the Server
— we expected to be completely straightforward. Quarkus REST client is
well-documented, the Server's API is simple, and we'd already written the
REST endpoints.

## What We Tried and What Happened

**The MCP server.** The MCP protocol is JSON-RPC over HTTP. For our use
case — synchronous tool calls from the controller Claude — a single
`POST /mcp` endpoint that receives a JSON-RPC request and returns a JSON-RPC
response is sufficient. No persistent SSE connection needed for simple tool
calls. The implementation uses Jackson's `JsonNode` for the request (avoiding
class-per-method-type proliferation) and a switch on the `method` field for
routing.

Eight tools implemented: `list_sessions`, `create_session`,
`delete_session`, `rename_session`, `send_input`, `get_output`,
`open_in_terminal`, `get_server_info`. All session management tools proxy
to the Server via `ServerClient`. The `open_in_terminal` tool calls
`TerminalAdapterFactory.resolve()` to find an available terminal, then
invokes it with the session's tmux name. The `get_server_info` tool reports
server URL, agent mode, and detected terminal adapter.

Testing the MCP server with `@InjectMock` on `ServerClient` worked well —
five tests covering initialize, tools/list, list_sessions proxy, create
proxy, and error handling all pass. The mocked approach is clean for unit
testing the routing logic.

**The integration gap.** After Plan 2 completed with 41 passing tests, we
did a live smoke test: ran the server in dev mode, hit `POST /mcp` with a
real `list_sessions` call. The response was:

```json
{
  "error": {
    "code": -32603,
    "message": "Response could not be mapped to type
    java.util.List<SessionResponse> for response with media type
    application/json. Hints: Consider adding
    quarkus-rest-client-reactive-jackson"
  }
}
```

The `quarkus-rest-client` extension alone does not include Jackson for JSON
deserialisation of REST responses. You need a separate extension:
`quarkus-rest-client-reactive-jackson`. Despite the "reactive" in the name,
this is the correct extension for the non-reactive `quarkus-rest-client`
when using the `quarkus-rest` stack. 42 mocked tests had passed because
`@InjectMock` on `ServerClient` bypasses actual HTTP calls entirely — the
deserialisation code never ran. The integration test caught it instantly.

This taught us something important: for any component that makes real HTTP
calls, you need at least one test that actually makes those calls. Mocking
at the boundary is good for unit testing logic, but it cannot catch
serialisation/deserialisation issues, missing extensions, or HTTP error
handling.

We added `McpServerIntegrationTest` — a `@QuarkusTest` where `ServerClient`
is NOT mocked. The Quarkus test server starts on port 8081, and
`application.properties` in the test resources configures the REST client
to point to `http://localhost:8081`. The MCP tool's HTTP call goes back to
the same Quarkus instance — the server calling itself. This covers the full
`MCP request → ServerClient → REST endpoint → tmux → response` chain.
Seven integration tests all pass.

**Terminal adapters.** The `ITerm2Adapter` uses AppleScript via `osascript`
to open a new iTerm2 window running `tmux -CC attach-session -t <name>`.
The `tmux -CC` flag is iTerm2's native tmux integration mode — the session
opens as native iTerm2 tabs rather than raw terminal text. Detecting iTerm2
availability also uses osascript to check the running process list.

**Clipboard detection.** The `ClipboardChecker` calls `tmux show-options -g
set-clipboard` to check current config, and if missing, appends
`set -g set-clipboard on` to `~/.tmux.conf` and reloads. This uses the OSC
52 clipboard protocol, which xterm.js supports via `@xterm/addon-clipboard`.

## What Changed and Why

Nothing structural changed. The architecture held. The Jackson discovery was
a runtime failure rather than a design problem, and the fix was additive
(one dependency). The integration test we added was something we should have
had from the start — it's now part of the standard test suite.

The `open_in_terminal` tool had one subtle simplification mid-implementation:
the original design had the adapter accept a `serverUrl` parameter for
potential remote terminal connections. In practice, the adapter just needs
the tmux session name — the terminal connects directly to tmux, not through
the web server. The interface was simplified accordingly.

## What We Now Believe

The Agent is working. 49 tests pass (42 unit + 7 integration), the MCP
server handles all eight tools, the terminal adapter auto-detects iTerm2,
and clipboard detection runs at startup.

The key lesson from this phase: for any component that makes real HTTP calls,
you need at least one test that actually makes those calls — even if it's
just calling the same server back on itself. Mocking at the boundary is good
for unit testing logic, but it cannot catch serialisation/deserialisation
issues, missing extensions, or HTTP error handling.

We're now ready for the part that will actually make this usable: the web
frontend and the terminal rendering problem.

---

**Next:** Plan 3 — the web dashboard, the PWA, and the discovery that
streaming a terminal through a browser is considerably harder than the
design made it look.
