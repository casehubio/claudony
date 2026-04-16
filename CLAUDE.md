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

# Install Chromium for browser E2E tests (one-time per machine; uses local Maven repo)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn dependency:copy-dependencies -DincludeGroupIds=com.microsoft.playwright -DoutputDirectory=/tmp/pw && \
  java -cp "/tmp/pw/*" com.microsoft.playwright.CLI install chromium

# Run browser E2E tests (requires Chromium installed above)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Pe2e -Dtest=PlaywrightSetupE2ETest,DashboardE2ETest,TerminalPageE2ETest

# Run with visible browser (local debugging)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Pe2e -Dplaywright.headless=false -Dtest=DashboardE2ETest

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
# Prod: session encryption key is auto-generated on first run and persisted to
# ~/.claudony/encryption-key — no env var needed. Set the env var only if you want
# to manage the key yourself (e.g. from a secrets manager); it takes precedence.
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

# Build Docker image (requires jar built first)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn package -DskipTests
docker build -t claudony:latest .

# Run two-node fleet with docker compose
export CLAUDONY_FLEET_KEY=$(openssl rand -base64 32)
docker compose up
# Node A: http://localhost:7777  Node B: http://localhost:7778
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
│   ├── fleet/
│   │   ├── PeerRegistry.java           — authoritative peer list, circuit breaker, atomic peers.json persistence
│   │   ├── PeerHealthScheduler.java    — @Scheduled health check loop, per-peer virtual thread
│   │   ├── PeerResource.java           — REST /api/peers (CRUD + /{id}/sessions + /{id}/ping + /generate-fleet-key)
│   │   ├── PeerClient.java             — @RegisterRestClient for peer /api/sessions calls
│   │   ├── StaticConfigDiscovery.java  — loads claudony.peers at startup
│   │   ├── ManualRegistrationDiscovery.java — REST-triggered peer management, persisted to peers.json
│   │   └── MdnsDiscovery.java          — mDNS advertise/discover (scaffold; full impl follow-on)
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
claudony.fleet-key=                     # shared secret for peer-to-peer API calls; generate with POST /api/peers/generate-fleet-key
claudony.peers=                         # comma-separated peer URLs for static discovery (e.g. http://mac-mini:7777)
claudony.mdns-discovery=false           # enable mDNS auto-discovery on LAN (scaffold; full impl follow-on)
claudony.name=Claudony                  # instance name shown in fleet dashboard
# Production — optional; auto-generated and persisted to ~/.claudony/encryption-key on first run.
# Set only if managing the key externally (secrets manager, etc.):
# QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY=<secret, >16 chars>
```

**Directory convention:** `~/.claudony/` holds config/credentials (hidden, system); `~/claudony-workspace/` is the default session working directory (visible, user-facing). Both are created on server startup.

---

## Test Count and Status

**214 tests passing** across:
- `SmokeTest` — basic health endpoint
- `server/` — TmuxService (real tmux), SessionRegistry, SessionResource, TerminalWebSocket, ServerStartup, SessionInputOutput
- `server/auth/` — ApiKeyService, ApiKeyAuthMechanism, AuthResource, AuthRateLimiter (+ AuthRateLimiterHttpTest for HTTP-level), CredentialStore, InviteService, FleetKeyService, FleetKeyAuth
- `config/` — EncryptionKeyConfigSource (15 unit tests + 5 QuarkusTest integration), SessionTimeoutConfigTest (3 QuarkusTest integration)
- `server/fleet/` — PeerRegistryTest (unit), StaticConfigDiscoveryTest (unit), MdnsDiscoveryTest (unit), PeerResourceTest (QuarkusTest + proxy resize), SessionFederationTest (QuarkusTest), ProxyWebSocketTest (QuarkusTest)
- `agent/` — McpServer (mocked), McpServerIntegrationTest (real HTTP), ServerClient, ClipboardChecker, ITerm2Adapter, TerminalAdapterFactory, AgentStartup
- `frontend/` — StaticFilesTest (all static files + content), AppAuthProtectionTest (/app/* unauthenticated), ResizeEndpointTest
- `e2e/` — ClaudeE2ETest (real `claude` CLI), PlaywrightSetupE2ETest (4 browser infra), DashboardE2ETest (7 dashboard UI), TerminalPageE2ETest (2: structure + proxy resize URL) — all via `mvn test -Pe2e -Dtest=...`, skipped in default run

**Browser test hook convention:** JavaScript that should only run during Playwright tests is gated behind `window.__CLAUDONY_TEST_MODE__`. Tests set it via `page.addInitScript("window.__CLAUDONY_TEST_MODE__ = true;")` before navigation. Never expose test hooks unconditionally.

`ServerStartup.bootstrapRegistry()` is package-private to allow direct testing.
Auth tests use `@TestSecurity(user = "test", roles = "user")` to bypass auth in non-auth test classes.
Stateful `@ApplicationScoped` beans (e.g. `AuthRateLimiter`) expose `resetForTest()` / `setClockForTest()` package-private hooks; `@AfterEach` cleanup is required to prevent state bleeding across `@QuarkusTest` classes, which share one app instance per test run.

---

## Design Document

`docs/DESIGN.md` is the living architectural overview — updated via `/update-design` (or automatically by `java-git-commit`). For point-in-time snapshots, see `docs/design-snapshots/`.

## Ecosystem Context

Claudony is the integration layer in a three-project Quarkus Native AI Agent Ecosystem:

- **CaseHub** (`~/claude/casehub`) — orchestration/choreography engine; defines SPIs that Claudony implements
- **Qhorus** (`~/claude/quarkus-qhorus`) — agent communication mesh; Claudony embeds it and provides the dashboard observation layer
- **Claudony** (this project) — wires everything together; implements CaseHub SPIs, embeds Qhorus, hosts the unified dashboard

The canonical ecosystem design document lives here in this repo. It is the master architectural blueprint for all three projects — covering project topology, SPI contracts, MCP tool surfaces, choreography/orchestration use cases, the unified observer dashboard, human-in-the-loop interjection, and the B→C→A build roadmap.

Load it when working on: CaseHub SPI implementations, Qhorus embedding, the unified MCP endpoint, the three-panel dashboard, or any cross-project architectural decisions:

@/Users/mdproctor/claude/claudony/docs/superpowers/specs/2026-04-13-quarkus-ai-ecosystem-design.md

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
