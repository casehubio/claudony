# Claudony — Design Document

**Status:** Active

**See also:** [Fleet Architecture](FLEET.md) — multi-instance peer mesh, Docker deployment, session federation

---

## Overview

Claudony lets you run Claude Code CLI sessions on one machine (a laptop or headless mini PC) and access them from any device via a browser or PWA. Sessions persist independently — closing a browser tab or iTerm2 window never kills a session.

A single Quarkus binary operates in two modes:

- **Server** — manages tmux sessions, streams terminal output over WebSocket, serves the web dashboard and REST API
- **Agent** — local MCP endpoint for a controller Claude instance; iTerm2 integration; proxies session commands to the Server via REST

---

## Component Structure

Three Maven modules: `claudony-core` (shared services), `claudony-casehub` (optional CaseHub integration), `claudony-app` (Quarkus application).

```
claudony-casehub — dev.claudony.casehub
├── CaseHubConfig                   — @ConfigMapping for claudony.casehub.*
├── WorkerCommandResolver           — capability→command lookup with default fallback
├── CaseLineageQuery                — interface for prior-worker lineage queries
├── EmptyCaseLineageQuery           — @DefaultBean stub (active when ledger not configured)
├── JpaCaseLineageQuery             — @Alternative @Priority(1) — queries case_ledger_entry
│                                     for WORKER_EXECUTION_COMPLETED events via qhorus PU
├── ClaudonyWorkerProvisioner       — WorkerProvisioner SPI: creates tmux sessions
├── ClaudonyCaseChannelProvider     — CaseChannelProvider SPI: Qhorus-backed channels
├── ClaudonyWorkerContextProvider   — WorkerContextProvider SPI: lineage + channel context
├── ClaudonyWorkerStatusListener    — WorkerStatusListener SPI: lifecycle → SessionRegistry
├── WorkerSessionMapping            — role↔session bridge (caseId:role→sessionId; role→sessionId fallback)
├── CaseChannelLayout               — SPI: List<ChannelSpec> channelsFor(caseId, definition)
│                                     ChannelSpec: purpose, ChannelSemantic, allowedTypes, description
├── NormativeChannelLayout          — default: work (APPEND, all types) / observe (APPEND, EVENT) /
│                                     oversight (APPEND, QUERY+COMMAND)
├── SimpleLayout                    — 2-channel: work + observe only (no human oversight)
├── MeshParticipationStrategy       — SPI: MeshParticipation strategyFor(workerId, context)
│                                     MeshParticipation enum: ACTIVE | REACTIVE | SILENT
├── ActiveParticipationStrategy     — default: register + STATUS + periodic check_messages
├── ReactiveParticipationStrategy   — engage only when directly addressed; skip registration
├── SilentParticipationStrategy     — no mesh participation
└── MeshSystemPromptTemplate        — package-private: generates ACTIVE full template, REACTIVE reduced,
                                     SILENT returns empty. Channel names from CaseChannelLayout; prior
                                     workers from CaseLineageQuery. Stored in WorkerContext.properties["systemPrompt"]

dev.claudony — claudony-core + claudony-app
├── config/
│   └── ClaudonyConfig          — all @ConfigProperty bindings
├── server/
│   ├── model/                  — Session (+ caseId, roleName for CaseHub workers),
│   │                             SessionStatus, SessionResponse, request records
│   ├── TmuxService             — ProcessBuilder wrappers for tmux commands
│   ├── SessionRegistry         — in-memory ConcurrentHashMap; findByCaseId() for case worker queries
│   ├── SessionResource         — REST /api/sessions (CRUD + resize + ?caseId= filter)
│   ├── TerminalWebSocket       — WebSocket /ws/{id}, pipe-pane + FIFO streaming
│   ├── ServerStartup           — startup health checks, tmux bootstrap
│   └── auth/
│       ├── ApiKeyAuthMechanism    — HttpAuthenticationMechanism for X-Api-Key header
│       ├── ApiKeyService          — key generation, file persistence, banner logging
│       ├── AuthRateLimiter        — sliding-window rate limiter on auth/WebAuthn paths
│       ├── AuthResource           — /auth/invite, /auth/register, /auth/login, /auth/dev-login
│       ├── CredentialStore        — WebAuthnUserProvider; reads/writes credentials.json
│       ├── InviteService          — one-time invite token generation and validation
│       ├── LenientNoneAttestation — Vert.x attestation override: accepts non-zero AAGUID
│       └── WebAuthnPatcher        — startup bean that swaps the "none" handler via reflection
├── agent/
│   ├── ServerClient            — typed REST client to Server (@RegisterRestClient)
│   ├── ApiKeyClientFilter      — injects X-Api-Key on all ServerClient calls
│   ├── McpServer               — JSON-RPC POST /mcp (8 session-management tools)
│   ├── ClipboardChecker        — tmux clipboard detection/fix
│   ├── AgentStartup            — Agent-mode startup checks
│   └── terminal/
│       ├── TerminalAdapter     — pluggable terminal interface
│       ├── ITerm2Adapter       — macOS AppleScript + tmux -CC
│       └── TerminalAdapterFactory — auto-detection (iterm2 | none)
└── (frontend — served from META-INF/resources/)
    ├── dashboard               — session card list; PR/CI badges; service health badges
    ├── terminal view           — xterm.js; compose overlay for multi-line input;
    │                             session.html has three-panel layout: case workers
    │                             (left), terminal (centre), channel panel (right)
    └── auth pages              — WebAuthn registration/login flows
```

---

## Architecture

### Source of Truth: tmux

Sessions live in tmux independently of the Quarkus process. On server restart, `ServerStartup.bootstrapRegistry()` reads `tmux list-sessions` and re-registers any session with the `claudony-` prefix. Working directory shows as "unknown" for bootstrapped sessions — expected and acceptable.

### Terminal Streaming (no PTY)

`tmux attach-session` requires a real PTY which ProcessBuilder cannot provide. Instead:

1. `tmux pipe-pane` redirects pane output to a named FIFO
2. A Java virtual thread reads the FIFO and writes to the WebSocket
3. Input goes via `tmux send-keys -t name -l "text"` (literal mode, `-l` flag required)

**History replay on reconnect:** `tmux resize-window` (not `resize-pane` — works for detached sessions with no attached clients) is called *first*, delivering SIGWINCH so TUI apps redraw before capture. After 150 ms, `tmux capture-pane -e -p -S -100` snapshots the post-resize state. Processing rules:
- Leading and trailing blank rows (scrollback / pane padding) are stripped
- Blank rows *within* the content range are **preserved** — removing them shifts xterm.js rows relative to tmux pane rows, breaking TUI absolute cursor positioning and producing duplicate prompts
- Visually blank rows that contain only ANSI codes are stored as empty strings
- A cursor-positioning escape `ESC[row;colH` (derived from `tmux display-message #{cursor_y} #{cursor_x}`) is appended so xterm.js cursor lands at the pane cursor after replay — not at the last text line
- The initial `\r\n` flush that pipe-pane sends on FIFO connect is swallowed (see BUGS-AND-ODDITIES #12) so it cannot move the cursor past the positioned point

### Session Expiry

Idle tmux sessions are cleaned up by `SessionIdleScheduler` (`@Scheduled every 5m`).
Expiry logic is pluggable via the `ExpiryPolicy` CDI interface:

| Policy | Name | Mechanism |
|---|---|---|
| `UserInteractionExpiryPolicy` | `user-interaction` | Checks `session.lastActive()` (default) |
| `TerminalOutputExpiryPolicy` | `terminal-output` | Checks `tmux display-message #{window_activity}` (tmux 3.6a: use `window_activity`, not `pane_activity` — the latter is blank without an attached client) |
| `StatusAwareExpiryPolicy` | `status-aware` | Never expires sessions where a non-shell process is running in the foreground |

Global default: `claudony.session-expiry-policy=user-interaction`. Per-session override via `CreateSessionRequest.expiryPolicy`. On expiry: `SessionExpiredEvent` CDI event fired first (WebSocket observer sends `{"type":"session-expired"}` to any connected clients on a virtual thread), then tmux session killed and registry entry removed — only if kill succeeds. `session.lastActive` is bumped by `SessionRegistry.touch()` on: WebSocket open (after pipe-pane setup), WebSocket input, REST `POST /api/sessions/{id}/input`.

### Virtual Threads and Blocking I/O

Claudony uses blocking I/O throughout — ProcessBuilder for tmux, Socket for health checks, FIFO reads for terminal streaming. The threading model that makes this safe:

| Context | Thread model | Blocking I/O? |
|---|---|---|
| REST endpoints (`@Path`) | Quarkus default executor (not event loop) | ✅ Safe — no `@Blocking` needed |
| WebSocket `onOpen()` setup | WebSocket handler (not Vert.x event loop) | ✅ Safe — short-lived ProcessBuilder calls |
| FIFO streaming | Explicit `Thread.ofVirtual().start(...)` | ✅ Safe — virtual thread owns the read loop |
| Service health checks | `Executors.newVirtualThreadPerTaskExecutor()` | ✅ Safe — parallel virtual threads |
| Vert.x event handlers (if added) | Vert.x event loop | ❌ Must use non-blocking API only |

**Rule:** Code on the Vert.x event loop must use non-blocking API or `@Blocking`. REST resources and WebSocket handlers are not on the event loop — blocking I/O there is safe by default.

### MCP Transport: HTTP JSON-RPC

The Agent exposes `POST /mcp` as a synchronous JSON-RPC endpoint. Claude Code connects to it as an MCP server via `--mcp-config`. HTTP transport is GraalVM-native compatible — no stdio subprocess needed. The `mcpServers` wrapper key in the config file is required (matches the `settings.json` schema); omitting it produces a silent schema validation error — no session is created.

### Dashboard Features

**PR/CI status** — fetched on demand (button click), not auto-polled. Avoids GitHub API rate limits; keeps the dashboard lightweight.

**Service health** — `Socket.connect()` with a 500 ms timeout, run in parallel via virtual threads. Works for any TCP service, not just HTTP endpoints.

**Compose overlay** — a full browser text editor overlaid on the terminal view for multi-line Claude Code prompts. Sends via `terminal.paste()` (xterm.js API) rather than raw WebSocket write — routes through the AttachAddon pipeline and handles bracketed paste mode correctly. Inserts at cursor position without pre-clearing the prompt. The iTerm2 open-session button is only shown when `window.location.hostname` is localhost — iTerm2 only works when server and browser are co-located.

---

## Authentication

### Mechanism

| Client | Mechanism | Detail |
|--------|-----------|--------|
| Browser / PWA | WebAuthn passkeys | `quarkus-security-webauthn`; Touch ID / Face ID; iCloud Keychain sync |
| Agent | `X-Api-Key` header | Auto-provisioned key; checked by `ApiKeyAuthMechanism` |

Both mechanisms are tried in turn on every request.

**API key provisioning:** `ApiKeyService` resolves the key via a priority chain: (1) explicit config property `claudony.agent.api-key`, (2) `~/.claudony/api-key` file, (3) auto-generate a random key. On first run the generated key is written to file with `chmod 600`, and a banner is logged to stdout. Both Server and Agent call `ApiKeyService.autoInit()` at startup so they share the same key via the filesystem without manual configuration.

**Apple passkey compatibility:** iCloud Keychain passkeys always respond with `fmt=none` attestation even when the server requests direct attestation, but include a non-zero AAGUID. Vert.x `NoneAttestation` rejects this. `WebAuthnPatcher` replaces the `"none"` handler in Vert.x's attestation map at startup with `LenientNoneAttestation`, which skips the AAGUID check while still enforcing that `attStmt` is empty. Valid session cookie or valid API key → authenticated as role `user`.

### Protected Routes

| Path | Protection |
|------|-----------|
| `/api/**` | `@Authenticated` (session cookie or API key) |
| `/ws/**` | Session cookie checked in `@OnOpen` |
| `/app/**` | `@Authenticated` → 302 to `/auth/login` if unauthenticated |
| `/auth/**`, `/q/**` | Public |

### Credential Storage

File: `~/.claudony/credentials.json` (configurable via `claudony.credentials-file`). Atomic writes (temp file + rename). Multiple credentials per username supported (multiple devices). `rw-------` permissions.

### Invite Flow

First user registers without a token (bootstrap). Subsequent users require a one-time UUID token generated by an authenticated user via `POST /auth/invite`. Tokens expire after 24 hours; consumed on first successful use.

### Rate Limiting

`AuthRateLimiter` applies a sliding 5-minute window (max 10 attempts per IP) to:
- `/q/webauthn/login/*` and `/q/webauthn/register/*` (WebAuthn ceremony)
- `/auth/register` (invite token endpoint)

Returns `429 Too Many Requests` with `Retry-After: 300` on breach. Implemented as a Vert.x route handler registered at startup.

---

## Data Flows

### Session creation (REST)

```
Client → POST /api/sessions → SessionResource
  → TmuxService.createSession() → tmux new-session -d -s name
  → SessionRegistry.add(session)
  → 201 Created { id, name, workingDir, status }
```

### Terminal streaming (WebSocket)

```
Browser → WS /ws/{id} → TerminalWebSocket.onOpen()
  → TmuxService.resizeWindow(cols, rows)    ← resize-window, not resize-pane (works for detached)
  → TmuxService.captureHistory() → send to client
  → TmuxService.pipePaneToFifo(name, fifo)
  → virtual thread reads FIFO → send to client
Browser keystroke → onMessage() → TmuxService.sendKeys(name, text)
```

### MCP tool call (Agent)

```
Claude → POST /mcp → McpServer.dispatch()
  → ServerClient.{method}() [REST client]
  → HTTP → Server /api/sessions/...
  → JSON response → MCP result
```

---

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Runtime | Java 21 API (compiled on Java 26 JVM) |
| Framework | Quarkus 3.32.2 |
| Native image | GraalVM 25 (native-image) |
| Terminal multiplexer | tmux |
| Terminal emulator (browser) | xterm.js |
| Auth | quarkus-security-webauthn + custom ApiKeyAuthMechanism |
| Build | Maven (not Maven wrapper) |
| TLS (deployment) | Caddy reverse proxy |

---

## Directory Conventions

| Path | Purpose |
|------|---------|
| `~/.claudony/` | System state — hidden, `rw-------` |
| `~/.claudony/credentials.json` | WebAuthn credentials (configurable via `claudony.credentials-file`) |
| `~/.claudony/api-key` | Auto-generated Agent API key; shared by Server and Agent |
| `~/claudony-workspace/` | Default session working directory — visible, user-facing |

Both created on server startup if absent.

---

## Key Constraints

- **No PTY in JVM** — `tmux attach-session` is not available; all terminal I/O goes through pipe-pane + FIFO
- **WebAuthn requires HTTPS** — Caddy handles TLS; Quarkus does not terminate TLS in production
- **Native image** — no reflection-based tricks; HTTP JSON-RPC for MCP (no stdio subprocess)
- **Session cookies survive restarts** — `quarkus.http.auth.session.encryption-key` must be set to a stable value (env var `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` in production). Without it, a random key is generated on startup, invalidating all sessions.

---

## Key Design Decisions

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Terminal streaming | `tmux pipe-pane` + named FIFO | ProcessBuilder cannot provide a PTY; pipe-pane works headless | `tmux attach-session` (requires PTY — fails in JVM) |
| History replay on reconnect | `tmux capture-pane -e -p -S -100` sent synchronously before pipe-pane | Avoids race condition; ANSI colour preserved | Replay after pipe-pane starts (race condition corrupted first char) |
| TUI redraw on connect | `tmux resize-window` to browser dimensions before pipe-pane starts | Delivers SIGWINCH; TUI redraws into live stream before user sees anything | Manual user resize (garbled until user acts) |
| MCP transport | HTTP JSON-RPC (`POST /mcp`) | GraalVM-native compatible; no stdio process needed | SSE, stdio MCP (incompatible with native or headless) |
| Browser auth | WebAuthn passkeys via `quarkus-security-webauthn` | No password to leak; Touch ID UX; invite-based onboarding | Passwords (extra secret to manage), basic auth (weak) |
| Agent→Server auth | `X-Api-Key` header | Simple, stateless, fits headless Agent | Session cookies (require browser-style session management) |
| Credential storage | JSON file at `~/.claudony/credentials.json` (atomic write, `rw-------`) | Self-contained, no database dependency | Database, system keychain |
| Directory convention | `~/.claudony/` config, `~/claudony-workspace/` sessions | Hidden dot-dir for system state; visible dir for user work | Single directory (mixes credentials with user files) |
| E2E test strategy | Side-effect assertions (tmux state) not LLM output | LLM output is non-deterministic; tmux state is not | Assert on Claude's words (fragile across model versions) |
| `--mcp-config` format | `mcpServers` wrapper required (same schema as `settings.json`) | Discovered via failed run; some plugin examples misleadingly omit it | Top-level server names (produces silent schema validation error) |
| Rate limiter placement | Vert.x `@Observes Router` handler | Covers WebAuthn ceremony paths (`/q/webauthn/*`) which bypass JAX-RS | JAX-RS `ContainerRequestFilter` (misses extension-managed paths) |
| Rate limiter state | Sliding window (`ArrayDeque<Instant>` per IP, synchronized) | Simple, in-memory, no dependency | Fixed window (allows burst at boundary), token bucket (more complex) |
| Clock injection | `Supplier<Instant>` field + `setClockForTest()` | Zero production overhead; avoids Mockito `mockStatic` or `Thread.sleep` | `java.time.Clock` injection (heavier), `mockStatic` (error-prone) |
| `@QuarkusTest` isolation | `resetForTest()` + `@AfterEach` in stateful beans | All `@QuarkusTest` classes share one app instance; without cleanup, state bleeds | No cleanup (causes misleading test failures in unrelated classes) |
| WebAuthn RP config keys | `relying-party.id`, `relying-party.name`, `origin` (singular) | Correct SmallRye Config field names from `WebAuthnRunTimeConfig` bytecode | `rp.id`, `rp.name`, `origins` — standard spec abbreviations but silently ignored |
| Session encryption key config | `quarkus.http.auth.session.encryption-key` | Actual `@ConfigItem` annotation name on `HttpConfiguration.encryptionKey` | `quarkus.http.encryption-key` — field name guess, silently ignored |
| Dev encryption key | Fixed value in `%dev` profile | Sessions survive restarts in dev — no re-authentication on every code change | Random key per startup |
| Apple passkey compatibility | `LenientNoneAttestation` via `WebAuthnPatcher` | iCloud Keychain passkeys always return `fmt=none` with non-zero AAGUID; Vert.x rejects this | Disable attestation entirely (weakens security model) |
| API key provisioning | First-run wizard: auto-generate, persist to `~/.claudony/api-key`, log banner | Zero config for same-machine setup; self-documenting; survives restarts | Env var in launchd plist (opaque); interactive stdin prompt (most work) |
| Key resolution order | Config property → file → auto-generate | Explicit always wins; file enables same-machine auto-discovery | Single-source (config only) — requires manual setup every time |
| Agent degraded mode | Warn prominently and start anyway | Consistent with "server not reachable" handling; 401 errors are self-explanatory | Hard fail on startup (breaks workflow when server isn't ready) |
| PR/CI status | On-demand fetch (button click) | Avoids GitHub API rate limits; keeps dashboard lightweight | Auto-refresh every 5s |
| Service health | `Socket.connect()` with 500ms timeout, parallel virtual threads | Works for any TCP service; fast | HTTP GET (HTTP-only, more complex) |
| Compose send method | `terminal.paste()` xterm.js API | Routes through AttachAddon; handles bracketed paste mode correctly | `ws.send()` — bypasses AttachAddon, breaks on reconnect |
| iTerm2 button visibility | `window.location.hostname` localhost-only check | iTerm2 only works when server is co-located with the browser | Show always (broken on mini PC) |
| Stable session encryption | `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` env var | Prevents cookie invalidation on server restart | Random key per restart (Quarkus default) |
| Project name | Claudony (from colony metaphor) | Unique, available; evokes controller/fleet concept; strong visual identity | RemoteCC (working name), other candidates checked for trademark conflicts |
| Visual identity | Bioluminescent colony — void-black, violet/green/magenta, organic SVG nodes | Colony metaphor maps naturally to sessions as organisms; distinctive from QuarkMind (hex + cyan) | Deep Space Colony (hex grid + cyan — too close to QuarkMind) |
| Landing page | Jekyll 4 in `docs/`, GitHub Pages via Actions | Follows project convention; Jekyll 4 needs Actions (classic pins 3.9) | Served from Quarkus app, separate domain |
| Two-mode binary | Single Quarkus binary, `claudony.mode=server\|agent` | Mac Mini hosts sessions; MacBook has iTerm2 — same binary, different roles | Two separate binaries |
| Internal rename | Clean cut — no backward compat | Personal tool, no external users; existing tmux sessions and `~/.remotecc/` abandoned | Dual-prefix support, migration script |

---

## Persistence — Named Datasource for Qhorus

Claudony uses a named datasource `qhorus` (configured via `quarkus.datasource.qhorus.*`) to segregate Qhorus schema from any Claudony-owned schema. Key config:

```properties
quarkus.datasource.qhorus.db-kind=h2
quarkus.datasource.qhorus.jdbc.url=jdbc:h2:file:~/.claudony/qhorus;...
quarkus.hibernate-orm.qhorus.datasource=qhorus
quarkus.hibernate-orm.qhorus.packages=io.casehub.qhorus.runtime,io.casehub.ledger.runtime.model,io.casehub.ledger.model
quarkus.flyway.qhorus.migrate-at-start=true
```

**CDI exclusion:** `casehub-ledger` ships `CaseLedgerEntryRepository` and `CaseLedgerEventCapture` as CDI beans that conflict with the `LedgerEntryRepository` already enabled by `casehub-ledger`. These are excluded via `quarkus.arc.exclude-types`; only `CaseLedgerEntry` (the JPA entity) is used — by `JpaCaseLineageQuery` in `claudony-casehub`.

Schema versioning is **Flyway-managed** — `database.generation` (Hibernate's auto-DDL) is not used. All Qhorus entities are in the `qhorus` persistence unit and route to Flyway for migrations.

**For the future:** The named datasource is the foundation for multi-node PostgreSQL fleets. Each Claudony instance points to the same remote Qhorus database, sharing channels and messages while keeping individual tmux sessions local.

---

## Testing

Current test count is tracked in CLAUDE.md (authoritative source). Three layers:
- **Unit tests** — plain JUnit, no Quarkus container; stateful beans use `resetForTest()` + `@AfterEach`
- **Integration tests** (`@QuarkusTest`) — full Quarkus context; all `@QuarkusTest` classes share one app instance; Qhorus data uses `InMemory*Store` implementations (from `casehub-qhorus-testing` dependency), no real database needed
- **E2E tests** — assert tmux session state (pane content, session existence), not Claude's output; LLM output is non-deterministic, tmux state is not

**Test cleanup pattern (Qhorus data):** Use `@Inject InMemoryChannelStore` and `@Inject InMemoryMessageStore`, then call `clear()` in `@AfterEach` to reset state between tests. This replaces the earlier `UserTransaction` + Panache pattern and works with the InMemory store implementations.

---

## Next Steps

- Dashboard redesign — apply Claudony colony theme (void-black, violet/green/magenta) to session cards and dashboard chrome
- xterm.js theming — expose configurable presets; don't force the colony palette inside the terminal pane (users have strong preferences: Nord, Solarized, Dracula)
- Lifecycle hooks — scripts on session create/delete

---

## Ecosystem Integration

> Claudony is the integration layer in a three-project Quarkus Native AI Agent Ecosystem alongside **CaseHub** (orchestration/choreography engine, `~/claude/casehub/engine`) and **Qhorus** (agent communication mesh, `~/claude/casehub/qhorus`). The canonical ecosystem design document lives at `docs/superpowers/specs/2026-04-13-quarkus-ai-ecosystem-design.md`.

### CaseHub SPIs — Shipped

All four CaseHub worker SPIs are implemented in the `claudony-casehub` module (enabled via `claudony.casehub.enabled=true`):

| CaseHub SPI | Claudony Implementation | Uses |
|---|---|---|
| `WorkerProvisioner` | `ClaudonyWorkerProvisioner` | `TmuxService` + `SessionRegistry` — creates/terminates Claude tmux sessions prefixed `claudony-worker-{uuid}` |
| `CaseChannelProvider` | `ClaudonyCaseChannelProvider` | Opens all channels defined by `CaseChannelLayout` on first touch (init-on-first-touch cache). Default: `NormativeChannelLayout` — work/observe/oversight channels, APPEND. Config: `claudony.casehub.channel-layout=normative\|simple` |
| `WorkerContextProvider` | `ClaudonyWorkerContextProvider` | Builds worker context: task, lineage, channel, `meshParticipation` stamped from `MeshParticipationStrategy`, and `systemPrompt` generated by `MeshSystemPromptTemplate`. Config: `claudony.casehub.mesh-participation=active\|reactive\|silent`, `claudony.casehub.channel-layout=normative\|simple` |
| `WorkerStatusListener` | `ClaudonyWorkerStatusListener` | tmux session lifecycle → `SessionRegistry` status transitions + `WorkerStalledEvent` CDI event |

**Lineage:** `JpaCaseLineageQuery` (`@Alternative @Priority(1)`) queries `case_ledger_entry` for `WORKER_EXECUTION_COMPLETED` events via the `qhorus` persistence unit. Returns empty until casehub-engine fires worker lifecycle `CaseLifecycleEvent`s — currently missing (tracked upstream).

**CDI wiring:** `casehub-ledger`'s own CDI beans (`CaseLedgerEntryRepository`, `CaseLedgerEventCapture`) are excluded via `quarkus.arc.exclude-types` to avoid `LedgerEntryRepository` ambiguity with casehub-ledger. Only `CaseLedgerEntry` (the JPA entity) is used directly.

**Design constraint:** `TmuxService` and `SessionRegistry` remain unaware of CaseHub. SPI implementations are the sole coupling point.

### Qhorus — Shipped

Qhorus is embedded as a Maven dependency. Its MCP tools join the Agent's MCP endpoint alongside Claudony's session tools. The named datasource `qhorus` (H2, `~/.claudony/qhorus`) is shared by Qhorus entities, the casehub-ledger schema, and `CaseLedgerEntry` — all Flyway-managed.

**Store SPI:** Six interfaces (`ChannelStore`, `MessageStore`, `SharedDataStore`, `InstanceStore`, `SharedDataIndexStore`, `PendingReplyStore`) — JPA in production, InMemory (`casehub-qhorus-testing`) in tests.

**Design constraint:** `McpServer` currently dispatches a hardcoded tool set. It must become composable (CDI `Instance<McpToolProvider>` or similar) before additional tool sources can be added cleanly.

### Three-Panel Dashboard — Upcoming (#75)

The dashboard evolves from its current session-card layout to a three-panel observatory:

```
┌──────────────┬───────────────────────┬────────────────────────┐
│  CASE GRAPH  │      TERMINAL         │      SIDE PANEL        │
│  (CaseHub)   │  (existing xterm.js)  │  CaseHub task + goal   │
│              │                       │  Lineage               │
│  worker list │                       │  Qhorus channel msgs   │
│  transitions │                       │  [Human interjection]  │
└──────────────┴───────────────────────┴────────────────────────┘
```

**Design constraint:** Keep `terminal.js` / `session.html` self-contained — it becomes the centre panel without requiring the side panels to exist. Left and right panels are additive.

### Human Interjection — Upcoming

The side panel includes a human input that posts to the Qhorus channel as a `human` sender. Workers see it on their next `check_messages` / `wait_for_reply` cycle; CaseHub records it in lineage as a `HumanDecision` node.

**Design constraint:** Human messages need distinct visual treatment from agent messages. Plan for `sender_type: human | agent` on rendered messages.

### Worker ↔ Session ↔ Channel Correlation — Partial (#76 shipped)

The triple link (tmux session ID ↔ CaseHub worker ID ↔ Qhorus channel name) is what makes the dashboard work — click a worker in the case graph, see their terminal and channel. `Session` now carries `caseId` and `roleName` (stamped by `ClaudonyWorkerProvisioner.provision()`), and `SessionRegistry.findByCaseId()` retrieves workers ordered by `createdAt`. `GET /api/sessions?caseId=` exposes this to the UI. The case worker panel in `session.html` displays the worker list and supports click-to-switch. Remaining: the full case graph with transitions, and the `qhorusChannel` link from session to Qhorus.

### Agent Mesh — Shipped (partial, epic #86)

Two SPIs control how Claudony-managed Claude agents engage with the Qhorus mesh:

**`CaseChannelLayout`** — defines *what channels* open when a case starts. Built-in:
- `NormativeChannelLayout` (default): `case-{id}/work` (all types), `case-{id}/observe` (EVENT only), `case-{id}/oversight` (QUERY+COMMAND)
- `SimpleLayout`: `case-{id}/work` + `case-{id}/observe` only (no human oversight channel)

**`MeshParticipationStrategy`** — defines *how* a worker engages at startup. Built-in:
- `ActiveParticipationStrategy` (default): register → announce → check messages periodically
- `ReactiveParticipationStrategy`: no registration; engage only when directly addressed
- `SilentParticipationStrategy`: no mesh participation

Both selected via config:

```properties
claudony.casehub.channel-layout=normative      # normative | simple
claudony.casehub.mesh-participation=active     # active | reactive | silent
```

`WorkerContext.properties["meshParticipation"]` is always present — "ACTIVE", "REACTIVE", or "SILENT".

`WorkerContext.properties["systemPrompt"]` is present for ACTIVE and REACTIVE workers when `caseId` is valid — the formatted mesh onboarding prompt generated by `MeshSystemPromptTemplate`. Contains: case header, ROLE, MESH CHANNELS (from `CaseChannelLayout`), STARTUP sequence (ACTIVE only), PRIOR WORKERS (from `JpaCaseLineageQuery`), and MESSAGE DISCIPLINE. SILENT workers and early-exit paths (clean-start, missing/malformed caseId) receive no prompt.

**`WorkerSessionMapping`** — bridges CaseHub role names to Claudony tmux session UUIDs. Two maps: `caseId:role→sessionId` (precise) and `role→sessionId` (fallback). Limitation: concurrent same-role workers across cases (#93, upstream engine change needed).

**Outstanding:** `MeshParticipationStrategy.strategyFor()` currently receives `null` for `context` (context not yet built at call time); shared-data keys from prior workers not yet included in the prompt (requires additional Qhorus integration, tracked as future work under epic #86).

### Guard Rails

- `TmuxService`, `SessionRegistry`, `TerminalWebSocket` — keep clean of CaseHub/Qhorus concepts
- SPI implementations are the coupling point, nothing else
- `McpServer` dispatch must become composable before additional tool sources are added
- Terminal component must remain independently functional (no hard dependency on side panels)

---

## Open Questions

- xterm.js theming: URL params, settings UI, or user preference stored in credentials file?
- Dashboard theme: user-configurable or fixed to the colony palette?
- CLI wrapper: when does the binary get `claudony server` / `claudony agent` instead of `-Dclaudony.mode=...`?
- Service health ports: should the checked port list be configurable per session, or is the fixed default set sufficient?
- Docker sandbox per session: worthwhile before wider use, or is tmux namespacing sufficient?
- Credential store multi-user: should it eventually support multiple named users, or remain single-owner?

---

## Related Documents

- [ADR-0001: Terminal streaming via pipe-pane and FIFO](adr/ADR-0001-terminal-streaming-pipe-pane-fifo.md)
- [ADR-0002: MCP transport via HTTP JSON-RPC](adr/ADR-0002-mcp-transport-http-json-rpc.md)
- [ADR-0003: Authentication via WebAuthn passkeys and API key](adr/ADR-0003-authentication-webauthn-api-key.md)
- [Auth design spec](superpowers/specs/2026-04-05-auth-design.md)
- [Landing page spec](superpowers/specs/2026-04-11-landing-page-design.md)
- [Known bugs and oddities](BUGS-AND-ODDITIES.md)
- Landing page: https://mdproctor.github.io/claudony/
