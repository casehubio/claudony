# RemoteCC — Claude Code Project Guide

## Project Type

**Type:** java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.9.5, GraalVM 25 (native image), tmux, xterm.js

---

## What This Project Is

RemoteCC lets you run Claude Code CLI sessions on one machine (MacBook or headless Mac Mini) and access them from any device via a browser or PWA. A "controller" Claude instance manages sessions via MCP. Sessions persist independently — closing a browser tab or iTerm2 window never kills a session.

Two Quarkus modes from the same binary:
- **Server** — owns tmux sessions, WebSocket terminal streaming, web dashboard, REST API
- **Agent** — local MCP endpoint for controller Claude, iTerm2 integration, clipboard detection

---

## Build and Test

```bash
# Run all tests
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test

# Run specific test
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=ClassName

# Run real Claude E2E tests (requires claude CLI authenticated)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Pe2e

# JVM build
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn package -DskipTests

# Native image build (requires GraalVM)
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn package -Pnative -DskipTests
```

**Use `mvn` not `./mvnw`** — the maven wrapper is broken on this machine.

---

## Running in Dev Mode

```bash
# Start server (dev mode, with hot reload)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -Dremotecc.mode=server

# ⚠️ IMPORTANT: Hot reload breaks WebSocket endpoint registration in Quarkus dev mode.
# After ANY Java commit that triggers a reload, do a full server restart.
# See docs/BUGS-AND-ODDITIES.md entry #1 for details.

# Start native binary
./target/remotecc-1.0.0-SNAPSHOT-runner                     # server mode (default)
./target/remotecc-1.0.0-SNAPSHOT-runner -Dremotecc.mode=agent -Dquarkus.http.port=7778
```

**Default ports:** Server = 7777, Agent = 7778

---

## Key URLs (dev mode)

- Dashboard: `http://localhost:7777/app/`
- Health: `http://localhost:7777/q/health`
- Sessions API: `http://localhost:7777/api/sessions`
- MCP endpoint (Agent): `http://localhost:7778/mcp`
- WebSocket terminal: `ws://localhost:7777/ws/{session-id}`
- Register passkey: `http://localhost:7777/auth/register` (first run, or with invite token)
- Login: `http://localhost:7777/auth/login`
- Dev quick login: `http://localhost:7777/auth/dev-login` (POST — dev mode only, sets auth cookie)

---

## Java and GraalVM on This Machine

```bash
# Java 26 (Oracle, system default) — use for dev and tests
JAVA_HOME=$(/usr/libexec/java_home -v 26)

# GraalVM 25 — use for native image builds only
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home

# GraalVM is also at: ~/.sdkman/candidates/java/25.0.2-graalce
# native-image is NOT on PATH by default — must set JAVA_HOME explicitly
```

Compiler target is `release=21` (Java 21 API surface) but we compile on Java 26.
Virtual threads (`Thread.ofVirtual()`) work fine on Java 26 with release=21.

---

## Project Structure

```
src/main/java/dev/remotecc/
├── config/RemoteCCConfig.java          — all config properties
├── server/
│   ├── model/                          — Session, SessionStatus, request/response records
│   ├── TmuxService.java                — ProcessBuilder wrappers for tmux commands
│   ├── SessionRegistry.java            — in-memory ConcurrentHashMap session store
│   ├── SessionResource.java            — REST API /api/sessions
│   ├── TerminalWebSocket.java          — WebSocket /ws/{id}, pipe-pane + FIFO streaming
│   ├── ServerStartup.java              — startup health checks, directory creation, tmux bootstrap
│   └── auth/
│       ├── ApiKeyAuthMechanism.java    — X-Api-Key header auth (Agent→Server) + dev cookie
│       ├── AuthResource.java           — /auth/register, /auth/login, /auth/dev-login
│       ├── CredentialStore.java        — WebAuthn credential persistence (~/.remotecc/credentials.json)
│       └── InviteService.java          — invite token generation and validation
└── agent/
    ├── ServerClient.java               — typed REST client to Server
    ├── ApiKeyClientFilter.java         — injects X-Api-Key on all ServerClient calls
    ├── McpServer.java                  — JSON-RPC POST /mcp (8 tools)
    ├── ClipboardChecker.java           — tmux clipboard detection/fix
    ├── AgentStartup.java               — Agent-mode startup checks
    └── terminal/
        ├── TerminalAdapter.java        — pluggable terminal interface
        ├── ITerm2Adapter.java          — macOS AppleScript + tmux -CC
        └── TerminalAdapterFactory.java — auto-detection

src/main/resources/META-INF/resources/  — static frontend served by Quarkus
├── manifest.json + sw.js              — PWA
└── app/
    ├── index.html + dashboard.js      — session management dashboard
    ├── session.html + terminal.js     — xterm.js terminal view + iPad key bar
    └── style.css                      — shared dark theme
```

---

## Architecture Notes

**tmux is the source of truth.** Sessions live in tmux independent of the Quarkus server. On server restart, `ServerStartup` bootstraps the registry from `tmux list-sessions` (sessions with `remotecc-` prefix). Working dir will show as "unknown" for bootstrapped sessions — this is expected.

**Terminal streaming (no PTY).** `tmux attach-session` requires a real PTY which ProcessBuilder cannot provide. We use `tmux pipe-pane` instead: pane output → FIFO → Java virtual thread → WebSocket. Input goes via `tmux send-keys -t name -l "text"` (the `-l` flag is critical — literal mode).

**History replay on reconnect.** Uses `tmux capture-pane -e -p -S -100` (ANSI colours). Lines are stripped of trailing whitespace (tmux pads to pane width), blank lines are removed (grid artefacts), joined with `\r\n` between lines (not after last), one trailing space restored on the last line (the current prompt). Sent synchronously BEFORE starting pipe-pane to avoid race conditions.

**MCP transport: HTTP/SSE.** The Agent exposes `POST /mcp` as a JSON-RPC endpoint. Claude Code connects to it as an MCP server. The Agent proxies session commands to the Server via REST. GraalVM-native compatible — no stdio process needed.

---

## Known Issues and Quirks

See `docs/BUGS-AND-ODDITIES.md` for comprehensive details. Key ones:

1. **Hot-reload breaks WebSocket** — full restart required after Java changes in dev mode
2. **One blank line after prompt on connect** — cosmetic, pipe-pane initial flush, harmless
3. **TUI apps (Claude Code, vim) history replay imperfect** — terminal resize triggers correct redraw
4. **Native binary staleness** — rebuild after adding new endpoints
5. **GraalVM not on PATH** — must set JAVA_HOME for native-image

---

## Configuration Properties

```properties
remotecc.mode=server|agent
remotecc.port=7777
remotecc.bind=localhost                  # use 0.0.0.0 for Mac Mini / remote access
remotecc.server.url=http://localhost:7777
remotecc.claude-command=claude
remotecc.tmux-prefix=remotecc-
remotecc.terminal=auto                   # auto|iterm2|none
remotecc.default-working-dir=~/remotecc-workspace   # default dir for new sessions
remotecc.credentials-file=~/.remotecc/credentials.json
remotecc.agent.api-key=                  # required in production; set via env var
```

**Directory convention:** `~/.remotecc/` holds config/credentials (hidden, system); `~/remotecc-workspace/` is the default session working directory (visible, user-facing). Both are created on server startup.

---

## Test Count and Status

**109 tests passing** across:
- `SmokeTest` — basic health endpoint
- `server/` — TmuxService (real tmux), SessionRegistry, SessionResource, TerminalWebSocket, ServerStartup, SessionInputOutput
- `server/auth/` — ApiKeyAuthMechanism, AuthResource, AuthRateLimiter, CredentialStore, InviteService
- `agent/` — McpServer (mocked), McpServerIntegrationTest (real HTTP), ServerClient, ClipboardChecker, ITerm2Adapter, TerminalAdapterFactory, AgentStartup
- `frontend/` — StaticFilesTest (all static files + content), ResizeEndpointTest
- `e2e/` — ClaudeE2ETest (real `claude` CLI via `mvn test -Pe2e`, skipped in default run)

`ServerStartup.bootstrapRegistry()` is package-private to allow direct testing.
Auth tests use `@TestSecurity(user = "test", roles = "user")` to bypass auth in non-auth test classes.

---

## Project Blog

Entries live in `docs/blog/`. Written using the personal technical writing
style guide at `~/claude-workspace/writing-styles/blog-technical.md`
(set `PERSONAL_WRITING_STYLES_PATH=~/claude-workspace/writing-styles`).

---

## What's Not Done Yet

- Authentication — WebAuthn passkey + API key implemented; rate limiting and dev-login backdoor closed; **session expiry not yet implemented** (sessions are effectively session cookies — expire on browser close or server restart)
- GitHub PR/CI integration in dashboard (idea logged)
- Docker sandbox per session (idea logged)
- Windows Terminal or Linux terminal adapters beyond iTerm2 (interface is pluggable, no implementation)
