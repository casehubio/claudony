# claudony — Consumer Guide

> Integration layer and operational dashboard — runs Claude Code CLI sessions remotely via tmux, wires CaseHub + Qhorus together, and surfaces everything in a browser/PWA workspace.

**GitHub:** [casehubio/claudony](https://github.com/casehubio/claudony)
**Tier:** Application (integration terminus)

---

## Purpose

Two modes from one binary: **server** (owns sessions, WebSocket streaming, dashboard) and **agent** (MCP endpoint for a controller Claude instance). Claudony is the integration terminus — nothing depends on it.

The terminal session is the starting point — how work gets done. Everything else is how you see, understand, and steer it:

- **Sessions** — fleet management, xterm.js terminals, persistent tmux sessions accessible from any device
- **Observation** — channels with rich conversation (speech acts, events, case-scoped and general chat)
- **Context** — case awareness, worker lineage, correlation chains, case browser
- **Action** — task inbox, commitments, interjections, human-in-the-loop steering

---

## Module Structure

| Module | Purpose |
|---|---|
| `claudony-core` | Session lifecycle management — tmux session control, registry, expiry policy SPI, `TenantContext` SPI with tenant-filtered `SessionRegistry` |
| `claudony-casehub` | Implements the casehub-engine worker provisioner SPIs — the CaseHub integration layer |
| `claudony-app` | Quarkus application: authentication, session API, WebSocket streaming, MCP server, fleet management, browser dashboard |

---

## Key Consumer APIs

### MCP Tools (Agent Mode)

The agent exposes 8 tools at `POST /mcp` (HTTP JSON-RPC, GraalVM-native compatible):
`list_sessions`, `create_session`, `send_input`, `read_output`, `open_in_terminal`, and session management tools.

A separate Qhorus MCP endpoint at `/qhorus` exposes 40+ agent mesh tools.

### REST API

| Endpoint | Purpose |
|---|---|
| `GET /api/sessions` | List sessions (supports `?caseId=` filter) |
| `POST /api/sessions` | Create session |
| `GET /api/sessions/{id}/lineage` | Worker lineage query |
| `GET /api/sessions/{id}/case-events` | SSE stream of case lifecycle events |
| `GET /api/channels` | List Qhorus channels |
| `POST /api/channels` | Create channel (served by Qhorus `ChannelResource`) |
| `GET /api/mesh/channels/{name}/members` | Channel membership |
| `GET /api/mesh/channels/{name}/presence` | Active SSE subscriber count |
| `GET /api/peers` | Fleet peer list |
| `WS /ws/{session-id}` | Terminal WebSocket |

### Authentication

- **Browser:** WebAuthn passkeys via `quarkus-security-webauthn`
- **Agent to Server:** `X-Api-Key` header (auto-generated on first run, saved to `~/.claudony/api-key`)
- **Rate limiting:** sliding-window rate limiter on WebAuthn paths

### CaseHub Integration (Optional)

Enabled via `claudony.casehub.enabled=true`. Implements all casehub-engine worker provisioner and execution SPIs:

| SPI Implementation | Engine SPI |
|---|---|
| `ClaudonyReactiveWorkerProvisioner` | `WorkerProvisioner` — creates tmux sessions running Claude CLI |
| `ClaudonyWorkerExecutionManager` | `WorkerExecutionManager` — virtual thread watcher for session exits |
| `ClaudonyCaseChannelProvider` | `CaseChannelProvider` — Qhorus-backed channels per case |
| `ClaudonyWorkerContextProvider` | `WorkerContextProvider` — builds startup prompt from ledger lineage |
| `ClaudonyWorkerStatusListener` | `WorkerStatusListener` — maps tmux lifecycle to worker states |

### Agent Mesh Framework

Claudony is the normative reference implementation of the CaseHub agent mesh. Channel layout and participation level are configurable:

| Config | Options | Default |
|---|---|---|
| `claudony.casehub.channel-layout` | `normative` (work/observe/oversight), `simple` (work/observe) | `normative` |
| `claudony.casehub.mesh-participation` | `active`, `reactive`, `silent` | `active` |

---

## Dependencies

| Repo | How |
|---|---|
| `casehub-qhorus` | Embedded directly; named `qhorus` datasource |
| `casehub-qhorus-postgres-broadcaster` | LISTEN/NOTIFY cross-instance event fan-out |
| `casehub-engine` | Implements its worker provisioner SPIs; `@WorkerBackend` qualifier |
| `casehub-ledger` | Transitively via Qhorus and casehub-ledger |

---

## What This Repo Does NOT Do

- Define orchestration rules (that is casehub-engine)
- Define agent messaging protocols (that is casehub-qhorus)
- Own audit ledger logic (that is casehub-ledger)
- Manage human task inboxes (that is casehub-work)
- Reimplement channel, message, or commitment logic — Qhorus handles all of that

The tmux session layer is deliberately kept free of CaseHub/Qhorus concepts. The CaseHub wiring lives in `claudony-casehub` as a clean SPI implementation layer.
