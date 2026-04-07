# RemoteCC — Design Document

**Last updated:** 2026-04-06
**Status:** Active

---

## Overview

RemoteCC lets you run Claude Code CLI sessions on one machine (MacBook or headless Mac Mini) and access them from any device via a browser or PWA. Sessions persist independently — closing a browser tab or iTerm2 window never kills a session.

A single Quarkus binary operates in two modes:

- **Server** — manages tmux sessions, streams terminal output over WebSocket, serves the web dashboard and REST API
- **Agent** — local MCP endpoint for a controller Claude instance; iTerm2 integration; proxies session commands to the Server via REST

---

## Component Structure

```
dev.remotecc
├── config/
│   └── RemoteCCConfig          — all @ConfigProperty bindings
├── server/
│   ├── model/                  — Session, SessionStatus, request/response records
│   ├── TmuxService             — ProcessBuilder wrappers for tmux commands
│   ├── SessionRegistry         — in-memory ConcurrentHashMap session store
│   ├── SessionResource         — REST /api/sessions (CRUD + resize)
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
└── agent/
    ├── ServerClient            — typed REST client to Server (@RegisterRestClient)
    ├── ApiKeyClientFilter      — injects X-Api-Key on all ServerClient calls
    ├── McpServer               — JSON-RPC POST /mcp (8 session-management tools)
    ├── ClipboardChecker        — tmux clipboard detection/fix
    ├── AgentStartup            — Agent-mode startup checks
    └── terminal/
        ├── TerminalAdapter     — pluggable terminal interface
        ├── ITerm2Adapter       — macOS AppleScript + tmux -CC
        └── TerminalAdapterFactory — auto-detection (iterm2 | none)
```

---

## Architecture

### Source of Truth: tmux

Sessions live in tmux independently of the Quarkus process. On server restart, `ServerStartup.bootstrapRegistry()` reads `tmux list-sessions` and re-registers any session with the `remotecc-` prefix. Working directory shows as "unknown" for bootstrapped sessions — expected and acceptable.

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

### MCP Transport: HTTP JSON-RPC

The Agent exposes `POST /mcp` as a synchronous JSON-RPC endpoint. Claude Code connects to it as an MCP server via `--mcp-config`. HTTP transport is GraalVM-native compatible — no stdio subprocess needed. The `mcpServers` wrapper key in the config file is required (matches the `settings.json` schema).

---

## Authentication

### Mechanism

| Client | Mechanism | Detail |
|--------|-----------|--------|
| Browser / PWA | WebAuthn passkeys | `quarkus-security-webauthn`; Touch ID / Face ID; iCloud Keychain sync |
| Agent | `X-Api-Key` header | Pre-shared key; checked by `ApiKeyAuthMechanism` |

Both mechanisms are tried in turn on every request.

**Apple passkey compatibility:** iCloud Keychain passkeys always respond with `fmt=none` attestation even when the server requests direct attestation, but include a non-zero AAGUID. Vert.x `NoneAttestation` rejects this. `WebAuthnPatcher` replaces the `"none"` handler in Vert.x's attestation map at startup with `LenientNoneAttestation`, which skips the AAGUID check while still enforcing that `attStmt` is empty. Valid session cookie or valid API key → authenticated as role `user`.

### Protected Routes

| Path | Protection |
|------|-----------|
| `/api/**` | `@Authenticated` (session cookie or API key) |
| `/ws/**` | Session cookie checked in `@OnOpen` |
| `/app/**` | `@Authenticated` → 302 to `/auth/login` if unauthenticated |
| `/auth/**`, `/q/**` | Public |

### Credential Storage

File: `~/.remotecc/credentials.json` (configurable via `remotecc.credentials-file`). Atomic writes (temp file + rename). Multiple credentials per username supported (multiple devices). `rw-------` permissions.

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
  → TmuxService.resizePane(cols, rows)
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
| Framework | Quarkus 3.9.5 |
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
| `~/.remotecc/` | System state (credentials.json, config) — hidden, `rw-------` |
| `~/remotecc-workspace/` | Default session working directory — visible, user-facing |

Both created on server startup if absent.

---

## Key Constraints

- **No PTY in JVM** — `tmux attach-session` is not available; all terminal I/O goes through pipe-pane + FIFO
- **WebAuthn requires HTTPS** — Caddy handles TLS; Quarkus does not terminate TLS in production
- **Native image** — no reflection-based tricks; HTTP JSON-RPC for MCP (no stdio subprocess)
- **Session cookies survive restarts** — `quarkus.http.auth.session.encryption-key` must be set to a stable value (env var `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` in production). Without it, a random key is generated on startup, invalidating all sessions.

---

## Related Documents

- [Auth design spec](superpowers/specs/2026-04-05-auth-design.md)
- [Known bugs and oddities](BUGS-AND-ODDITIES.md)
- [Design snapshots](design-snapshots/) — immutable point-in-time records
