# Claudony — Claude Code Project Guide

## Project Type

**Type:** java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.9.5, GraalVM 25 (native image), tmux, xterm.js

---

## What This Project Is

Claudony lets you run Claude Code CLI sessions on one machine (MacBook or headless Mac Mini) and access them from any device via a browser or PWA. A "controller" Claude instance manages sessions via MCP. Sessions persist independently — closing a browser tab or iTerm2 window never kills a session.

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
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -Dclaudony.mode=server

# ⚠️ IMPORTANT: Hot reload breaks WebSocket endpoint registration in Quarkus dev mode.
# After ANY Java commit that triggers a reload, do a full server restart.
# See docs/BUGS-AND-ODDITIES.md entry #1 for details.

# Start JVM jar (IMPORTANT: -D flags must come BEFORE -jar, not after)
# Also set QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY so auth cookies survive restarts.
# Without it, a new random key is generated each restart and all sessions are logged out.
# The application.properties has a fallback dev key, but prod mode generates a random one.
QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY=<your-secret-32-chars> \
JAVA_HOME=$(/usr/libexec/java_home -v 26) java \
  -Dclaudony.mode=server -Dclaudony.bind=0.0.0.0 \
  -jar target/quarkus-app/quarkus-run.jar

JAVA_HOME=$(/usr/libexec/java_home -v 26) java \
  -Dclaudony.mode=agent -Dclaudony.port=7778 \
  -jar target/quarkus-app/quarkus-run.jar

# Start native binary
./target/claudony-1.0.0-SNAPSHOT-runner                     # server mode (default)
./target/claudony-1.0.0-SNAPSHOT-runner -Dclaudony.mode=agent -Dquarkus.http.port=7778
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
src/main/java/dev/claudony/
├── config/ClaudonyConfig.java          — all config properties
├── server/
│   ├── model/                          — Session, SessionStatus, request/response records
│   ├── TmuxService.java                — ProcessBuilder wrappers for tmux commands
│   ├── SessionRegistry.java            — in-memory ConcurrentHashMap session store
│   ├── SessionResource.java            — REST API /api/sessions
│   ├── TerminalWebSocket.java          — WebSocket /ws/{id}, pipe-pane + FIFO streaming
│   ├── ServerStartup.java              — startup health checks, directory creation, tmux bootstrap
│   └── auth/
│       ├── ApiKeyService.java          — key resolution (config → file → generate), first-run banner
│       ├── ApiKeyAuthMechanism.java    — X-Api-Key header auth (Agent→Server) + dev cookie
│       ├── AuthResource.java           — /auth/register, /auth/login, /auth/dev-login
│       ├── CredentialStore.java        — WebAuthn credential persistence (~/.claudony/credentials.json)
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

**tmux is the source of truth.** Sessions live in tmux independent of the Quarkus server. On server restart, `ServerStartup` bootstraps the registry from `tmux list-sessions` (sessions with `claudony-` prefix). Working dir will show as "unknown" for bootstrapped sessions — this is expected.

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
claudony.mode=server|agent
claudony.port=7777
claudony.bind=localhost                  # use 0.0.0.0 for Mac Mini / remote access
claudony.server.url=http://localhost:7777
claudony.claude-command=claude
claudony.tmux-prefix=claudony-
claudony.terminal=auto                   # auto|iterm2|none
claudony.default-working-dir=~/claudony-workspace   # default dir for new sessions
claudony.credentials-file=~/.claudony/credentials.json
claudony.agent.api-key=                  # auto-generated on first server run; saved to ~/.claudony/api-key
# Production — must also set via env var (no default; random key = sessions lost on restart):
# QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY=<secret, >16 chars>
```

**Directory convention:** `~/.claudony/` holds config/credentials (hidden, system); `~/claudony-workspace/` is the default session working directory (visible, user-facing). Both are created on server startup.

---

## Test Count and Status

**139 tests passing** across:
- `SmokeTest` — basic health endpoint
- `server/` — TmuxService (real tmux), SessionRegistry, SessionResource, TerminalWebSocket, ServerStartup, SessionInputOutput
- `server/auth/` — ApiKeyService, ApiKeyAuthMechanism, AuthResource, AuthRateLimiter (+ AuthRateLimiterHttpTest for HTTP-level), CredentialStore, InviteService
- `agent/` — McpServer (mocked), McpServerIntegrationTest (real HTTP), ServerClient, ClipboardChecker, ITerm2Adapter, TerminalAdapterFactory, AgentStartup
- `frontend/` — StaticFilesTest (all static files + content), AppAuthProtectionTest (/app/* unauthenticated), ResizeEndpointTest
- `e2e/` — ClaudeE2ETest (real `claude` CLI via `mvn test -Pe2e`, skipped in default run)

`ServerStartup.bootstrapRegistry()` is package-private to allow direct testing.
Auth tests use `@TestSecurity(user = "test", roles = "user")` to bypass auth in non-auth test classes.
Stateful `@ApplicationScoped` beans (e.g. `AuthRateLimiter`) expose `resetForTest()` / `setClockForTest()` package-private hooks; `@AfterEach` cleanup is required to prevent state bleeding across `@QuarkusTest` classes, which share one app instance per test run.

---

## Design Document

`docs/DESIGN.md` is the living architectural overview — updated via `/update-design` (or automatically by `java-git-commit`). For point-in-time snapshots, see `docs/design-snapshots/`.

## Ecosystem Context

Claudony is the integration layer in a three-project Quarkus Native AI Agent Ecosystem (CaseHub + Qhorus + Claudony). Load the ecosystem design only when working on CaseHub SPI implementations, Qhorus embedding, the unified MCP endpoint, or the three-panel dashboard:

@/Users/mdproctor/claude/cross-claude-mcp/docs/superpowers/specs/2026-04-13-quarkus-ai-ecosystem-design.md

---

## Project Blog

Entries live in `docs/blog/`. Written using the personal technical writing
style guide at `~/claude-workspace/writing-styles/blog-technical.md`
(set `PERSONAL_WRITING_STYLES_PATH=~/claude-workspace/writing-styles`).

---

## Landing Page

The public site lives at `https://mdproctor.github.io/claudony/` and is a Jekyll 4 site in `docs/`.

**Deployed automatically** via `.github/workflows/jekyll.yml` on every push to `main` — no manual step needed.

**Structure:**
- `docs/_posts/` — blog entries (Jekyll format, mirrors `docs/blog-archive/`)
- `docs/guide/index.md` — getting started guide
- `docs/_layouts/` — page templates
- `docs/assets/` — CSS and JS

**Local development:**
```bash
cd docs && bundle exec jekyll serve --baseurl ""
# → http://localhost:4000
```

**Ruby:** Use Homebrew Ruby (`/opt/homebrew/opt/ruby/bin/bundle`) — system Ruby 2.6 is too old for Jekyll 4.

---

## What's Not Done Yet

- Authentication — WebAuthn passkey + API key implemented; rate limiting and dev-login backdoor closed; **session expiry not yet implemented** (sessions are session cookies — expire on browser close; server restarts no longer invalidate sessions since encryption key is now persistent)
- GitHub PR/CI integration in dashboard (idea logged)
- Docker sandbox per session (idea logged)
- Windows Terminal or Linux terminal adapters beyond iTerm2 (interface is pluggable, no implementation)

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** mdproctor/claudony
**Changelog:** GitHub Releases (run `gh release create --generate-notes` at milestones)

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — when the user says "implement", "start coding",
  "execute the plan", "let's build", or similar: check if an active issue or epic
  exists. If not, run issue-workflow Phase 1 to create one **before writing any code**.
- **Before writing any code** — check if an issue exists for what's about to be
  implemented. If not, draft one and assess epic placement (issue-workflow Phase 2)
  before starting. Also check if the work spans multiple concerns.
- **Before any commit** — run issue-workflow Phase 3 (via git-commit) to confirm
  issue linkage and check for split candidates. This is a fallback — the issue
  should already exist from before implementation began.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).
  If the user explicitly says to skip ("commit as is", "no issue"), ask once to confirm
  before proceeding — it must be a deliberate choice, not a default.
