# Retrospective Issue Mapping — RemoteCC

**Generated:** 2026-04-08  
**Repo:** mdproctor/remotecc  
**Commits analysed:** 107 (2026-04-03 → 2026-04-07)  
**Method:** conventional-commit scope clustering + time-window boundary detection

---

## Phase Boundaries

| Boundary | Date | Signal |
|----------|------|--------|
| Bootstrap | Apr 3 | First commit — project scaffolding |
| Server + Agent + Frontend sprint | Apr 4 | Dense cluster, 40+ commits |
| Testing + Auth foundation | Apr 5 | Distinct shift in commit subject matter |
| Auth hardening | Apr 6 | Rate limiting, production fixes |
| API key provisioning | Apr 7 | New feature cluster + issue tracking setup |

---

## Epics and Child Issues

### Epic 1: Build Server Core and Session Management

**Covers:** 2026-04-03 → 2026-04-05  
**Definition of Done:** Sessions persist in tmux independent of the Quarkus server; REST API manages full lifecycle; server bootstraps from tmux on restart; duplicate detection and config directory structure in place.

#### #8 — Bootstrap Quarkus dual-mode project with config and core data model

**Label:** `enhancement`  
**Commits:** `dca6ff3`, `e57cf46`, `bd6c7b2`, `d3c43b3`  
**Key commits:** `dca6ff3` Bootstrap Quarkus project / `bd6c7b2` Add Session record and SessionStatus enum / `e57cf46` Set Java compiler release to 21

#### #9 — Implement TmuxService, SessionRegistry, and server startup bootstrap

**Label:** `enhancement`  
**Commits:** `6a55c35`, `4fa7ffde`, `bf0655ba`, `cd2966bd`, `8e9e2bbf`  
**Key commits:** `6a55c35` Add TmuxService / `bf0655ba` Add in-memory SessionRegistry / `cd2966bd` Bootstrap registry from tmux sessions on startup

#### #10 — Add Session REST API (list, get, create, delete, rename, send_input, get_output)

**Label:** `enhancement`  
**Commits:** `4c4581927`, `e6943b8`  
**Key commits:** `4c4581927` Session REST API / `e6943b8` Add send_input and get_output endpoints

#### #11 — Add session UX improvements (duplicate detection, config/workspace directory split)

**Label:** `enhancement`  
**Commits:** `2a5be544`, `79131677`, `f873c89f`  
**Key commits:** `2a5be544` Duplicate session name detection — 409 + live UI validation / `79131677` Split ~/.remotecc (config) from ~/remotecc-workspace (sessions)

---

### Epic 2: Build PTY-Free Terminal Streaming with pipe-pane

**Covers:** 2026-04-04 → 2026-04-07  
**Definition of Done:** Terminal sessions stream from tmux to browser via WebSocket with no PTY; history replays correctly on reconnect for both shell and TUI apps (Claude Code, vim); resize works.

#### #12 — Build WebSocket terminal streaming via pipe-pane and FIFO (no PTY required)

**Label:** `enhancement`  
**Commits:** `bf5eecc0`, `199dc8fe`, `5176cdd2`  
**Key commits:** `bf5eecc0` Initial WebSocket attempt (tmux attach, required PTY) / `199dc8fe` Replace with pipe-pane+FIFO approach / `5176cdd2` Extract cleanup() for @OnError

#### #13 — Fix terminal history replay and TUI rendering on reconnect

**Label:** `bug`  
**Commits:** `99093b58`, `68155d0f`, `98f3bb95`, `39184b26`, `107af6c3`, `8466cfb`, `c0275d47`, `ed95a3a5`, `23ffa4df`, `7f38174`, `e25ef673`, `d64a632`, `34c312a`  
**Key commits:** `99093b58` First history replay attempt / `199dc8fe` pipe-pane fix / `ed95a3a5` Use capture-pane -e for ANSI colours / `7f38174` Trailing space for cursor position / `e25ef673` Auto-resize on connect (fixes TUI garble) / `d64a632` Fix TUI apps + Apple passkeys

---

### Epic 3: Build Agent and MCP Control Plane

**Covers:** 2026-04-04  
**Definition of Done:** Controller Claude can manage RemoteCC sessions via MCP tools; Agent proxies to Server via authenticated REST; iTerm2 opens sessions; clipboard configured automatically.

#### #14 — Add ServerClient REST client for Agent-to-Server communication

**Label:** `enhancement`  
**Commits:** `c08ff082`, `b2273b66`  
**Key commits:** `c08ff082` ServerClient interface + test mock support / `b2273b66` Add quarkus-rest-client-reactive-jackson

#### #15 — Build MCP JSON-RPC server with 8 session management tools

**Label:** `enhancement`  
**Commits:** `1184204e`, `6ebcac9c`  
**Key commits:** `1184204e` MCP server with all 8 tools at POST /mcp / `6ebcac9c` AgentStartup with connectivity and adapter detection

#### #16 — Add iTerm2 terminal adapter and tmux clipboard auto-detection

**Label:** `enhancement`  
**Commits:** `5aafd28b`, `b4d6e9b6`, `f1dcfdd7`  
**Key commits:** `5aafd28b` Terminal adapter interface + ITerm2Adapter + factory / `b4d6e9b6` ClipboardChecker / `f1dcfdd7` ApiKeyClientFilter — Agent injects X-Api-Key on all ServerClient calls

---

### Epic 4: Build Web Dashboard and PWA

**Covers:** 2026-04-04  
**Definition of Done:** Browser dashboard manages sessions; xterm.js terminal view works; app installable to iPad home screen as PWA.

#### #17 — Set up frontend structure with xterm.js terminal view and resize endpoint

**Label:** `enhancement`  
**Commits:** `1ea74cf4`  
**Key commits:** `1ea74cf4` Static resource structure, session.html terminal view, resize endpoint, frontend test scaffolding

#### #18 — Build session management dashboard with create dialog and auto-refresh

**Label:** `enhancement`  
**Commits:** `d0e11f92`  
**Key commits:** `d0e11f92` Management dashboard — session cards, create dialog, auto-refresh

#### #19 — Add PWA manifest, service worker, and iPad home screen support

**Label:** `enhancement`  
**Commits:** `4174de26`  
**Key commits:** `4174de26` PWA manifest, service worker registration, SVG icons for iPad

---

### Epic 5: Implement WebAuthn Passkey Authentication

**Covers:** 2026-04-05 → 2026-04-06  
**Definition of Done:** Browser sessions require passkey login; server protected by API key auth; invite-only registration; rate limiting on auth endpoints; dev login available in dev mode only; session encryption key persists across restarts.

#### #20 — Add authentication foundation — WebAuthn, CredentialStore, InviteService, API key auth

**Label:** `enhancement`  
**Commits:** `ac80d56b`, `4e2faca5`, `b9bd6847`, `1493d20d`, `dcdcfd20`, `abeed7b8`, `1131cfb5`, `88696208`  
**Key commits:** `ac80d56b` Add quarkus-security-webauthn + config / `4e2faca5` InviteService (24h one-time tokens) / `b9bd6847` CredentialStore (JSON-backed WebAuthnUserProvider) / `1493d20d` ApiKeyAuthMechanism — protect /api/* / `dcdcfd20` AuthResource — POST /auth/invite + GET /auth/register / `abeed7b8` Login and register HTML pages

#### #21 — Fix authentication security issues from review

**Label:** `bug`  
**Commits:** `5e97a5b7`, `de2a82f6`, `ad8933b2`  
**Key commits:** `5e97a5b7` Consume invite token, timing-safe comparison, file permissions, JSON serialization / `de2a82f6` Redirect to /auth/login on 401 in dashboard.js / `ad8933b2` Dev quick login overlay (dev mode only)

#### #22 — Harden authentication for production (rate limiting, /app/* protection)

**Label:** `security`  
**Commits:** `06c40319`, `26ac367c`, `b33bdc30`  
**Key commits:** `06c40319` Rate limiting, /app/* protection, dev cookie fix / `26ac367c` HTTP-level rate limiter test + dev cookie rejection test / `b33bdc30` Close remaining test gaps (window expiry, credential interface, /app/* protection)

#### #23 — Fix WebAuthn session encryption key and property names

**Label:** `bug`  
**Commits:** `0fcda8cc`, `644f082`, `623ab692`  
**Key commits:** `0fcda8cc` Correct WebAuthn property names / `644f082` Set persistent WebAuthn session encryption key / `623ab692` Update design doc — encryption key now configurable

---

### Epic 6: Add E2E Testing and Test Coverage Hardening

**Covers:** 2026-04-05  
**Definition of Done:** All existing endpoints covered by tests; real Claude CLI invoked in E2E suite via -Pe2e profile; sendKeys -l regression covered; 81+ tests passing.

#### #24 — Expand unit and integration test coverage across all modules

**Label:** `enhancement`  
**Commits:** `98bd3e0d`, `e8fb8601`, `36439ca3`, `0d1535dd`, `76b8bc6b`, `97d343cd`, `build-exclude`  
**Key commits:** `98bd3e0d` Rename + resize endpoint tests / `e8fb8601` 5 McpServer unit tests / `36439ca3` 5 McpServerIntegration tests / `0d1535dd` History replay + concurrent connections / `76b8bc6b` Explicit bootstrap test (makes bootstrapRegistry package-private) / `97d343cd` Exclude *E2ETest from default surefire, add -Pe2e profile

#### #25 — Add real Claude CLI E2E test suite with -Pe2e profile

**Label:** `enhancement`  
**Commits:** `df621245`, `ad7dcd7c`  
**Key commits:** `df621245` ClaudeE2ETest — real claude CLI invocations against MCP server / `ad7dcd7c` Correct MCP config format (mcpServers wrapper required)

#### #26 — Fix tmux sendKeys -l flag bug and add regression test

**Label:** `bug`  
**Commits:** `9d357f08`, `15ed868`, `3a300f9a`  
**Key commits:** `9d357f08` Use -l flag in TmuxService.sendKeys (prevents key name interpretation) / `15ed868` Add explaining comment / `3a300f9a` End-to-end test proving -l fix works through REST

---

### Epic 7: Implement Auto-generated API Key Provisioning

**Covers:** 2026-04-07  
**Definition of Done:** On first server run, an API key is auto-generated, persisted to ~/.remotecc/api-key, and displayed in a first-run banner; Agent reads same key on startup; no manual config needed.

#### #27 — Add ApiKeyService — key resolution, generation, and file persistence

**Label:** `enhancement`  
**Commits:** `d3f7564f`, `bd416c5`, `ffa5b460`, `ddfb40ff`, `c27fc41b`  
**Key commits:** `d3f7564f` ApiKeyService core (config → file → generate chain) / `bd416c5` Guard banner on persist failure, POSIX chmod guard, blank file test / `ffa5b460` Clarify autoInit Javadoc

#### #28 — Wire ApiKeyService into server startup, agent startup, and auth mechanism

**Label:** `enhancement`  
**Commits:** `e9ae4f99`, `97b303fe`, `1c6e3a19`, `f0d34a22`  
**Key commits:** `e9ae4f99` Wire into ApiKeyAuthMechanism and ApiKeyClientFilter / `97b303fe` Call from ServerStartup and AgentStartup

---

## Standalone Issues

### #29 — Add BUGS-AND-ODDITIES.md project knowledge base

**Label:** `documentation`  
**Commits:** `97d064f9`  
Comprehensive catalogue of bugs, quirks, and gotchas from initial development — covers hot-reload WebSocket break, pipe-pane vs attach-session, capture-pane behaviour, and 10+ more entries.

### #30 — Enable GraalVM native image with reflection config

**Label:** `enhancement`  
**Commits:** `435b094f`, `35b8dde1`  
Add native image reflection config for model classes; verify GraalVM 25 native build produces a working binary.

### #31 — Add .gitignore for build artifacts and worktrees

**Label:** `refactor`  
**Commits:** `780f708c`, `ba36934c`  
Add .gitignore to exclude target/, .worktrees/; fix subsequent chore.

### #32 — Add project development blog (11 entries)

**Label:** `documentation`  
**Commits:** `280eca4e`, `ddd9435b`, `ad73a90e`, `1c5dfd28`, `30ebb9d`, `8837690b`, `f8f0bb5`  
Five initial blog entries covering the full development journey from iPad itch to terminal rendering saga, then six more across auth hardening, testing, and provisioning. Renamed to docs/blog/ with author initials prefix.

### #33 — Enable GitHub issue tracking

**Label:** `enhancement`  
**Commits:** `e7ad94c`  
Add Work Tracking section to CLAUDE.md; configure GitHub repo reference and automatic issue-enforcement behaviours.

---

## Excluded Commits

These are operational meta-artifacts (session management tooling, not project features) or one-line doc annotations with no substantive content.

| Commit | Message | Reason |
|--------|---------|--------|
| `77e847e` | docs: session handover 2026-04-07 session 2 | Operational — session HANDOVER.md |
| `6dcb031` | docs: session handover 2026-04-07 | Operational — session HANDOVER.md |
| `ddfb40ff` (HANDOVER) | docs: session handover 2026-04-07 | Operational (note: spec commit shares prefix — spec rolled into Epic 7) |
| `fe6fa3f5` | docs: session wrap 2026-04-07 | Operational — wrap commit |
| `ae65a6ef` | docs: session handover 2026-04-06 | Operational — session HANDOVER.md |
| `670952774` | docs: session wrap 2026-04-06 | Operational — wrap commit |
| `6613c19c` | docs: session handover 2026-04-06 | Operational — session HANDOVER.md |
| `eebf0b93` | docs: session handover 2026-04-06 | Operational — session HANDOVER.md |
| `50b76366` | docs: session handover 2026-04-05 | Operational — session HANDOVER.md |
| `8e9e2bbf` | docs: add CLAUDE.md and session handiff | Rolled into #9 (server startup knowledge) |
| `7fa4a9fe` | docs: add design snapshot 2026-04-07 | Operational — frozen design state |
| `8037c273` | docs: add design snapshot 2026-04-06-post-blog-catchup | Operational — frozen design state |
| `642d611f` | docs: add design snapshot 2026-04-06-full-system-state | Operational — frozen design state |
| `f873c89f` | docs: update CLAUDE.md — 106 tests | Doc-only number update |
| `5d959a8b` | docs: update CLAUDE.md — 81 tests, -Pe2e | Doc-only number update |
| `0c6b1940` | docs: add Project Blog section to CLAUDE.md | Minor config addition, no code |
| `d3c43b3` | docs: add comment noting Java 21 requirement | One-line code comment |

---

## Issue Count Summary

| Type | Count |
|------|-------|
| Epics | 7 |
| Child issues | 20 |
| Standalone issues | 5 |
| **Total issues** | **32** |
| Excluded commits | 17 |
