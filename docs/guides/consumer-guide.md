# claudony -- Consumer Guide

> Work platform for agents and humans -- runs Claude Code CLI sessions remotely via tmux, wires CaseHub + Qhorus together, and surfaces everything in a browser/PWA workspace.

**GitHub:** [casehubio/claudony](https://github.com/casehubio/claudony)
**Tier:** Application (integration terminus)

---

## Purpose

Two modes from one binary: **server** (owns sessions, WebSocket streaming, dashboard) and **agent** (MCP endpoint for a controller Claude instance). Claudony is the integration terminus -- nothing depends on it.

The terminal session is the starting point -- how work gets done. Everything else is how you see, understand, and steer it:

- **Sessions** -- fleet management, xterm.js terminals, persistent tmux sessions accessible from any device
- **Observation** -- channels with rich conversation (speech acts, reactions, topics, events, case-scoped and general chat)
- **Context** -- case awareness, worker lineage, correlation chains, commitments
- **Action** -- task inbox, commitments, interjections, human-in-the-loop steering

---

## Module Structure

| Module | Artifact | Purpose |
|---|---|---|
| `claudony-core` | `claudony-core` | Session lifecycle -- tmux session control, `SessionRegistry` (tenant-filtered), `ExpiryPolicy` SPI (3 implementations), `TenantContext` SPI with `DefaultTenantContext`, `WorkerCaseLifecycleEvent` CDI bridge |
| `claudony-casehub` | `claudony-casehub` | CaseHub SPI implementations -- provisioning, execution watching, channel management, context building, status mapping, ledger event capture, mesh framework, causal link resolution |
| `claudony-app` | `claudony` (runnable) | Quarkus application: authentication, session API, WebSocket streaming, MCP server, fleet management, channel observation, browser dashboard (Quinoa + esbuild + Lit 3) |

---

## Key Consumer APIs

### MCP Tools (Agent Mode)

The agent exposes **8 session management tools** at `POST /mcp` (HTTP JSON-RPC, GraalVM-native compatible):

| Tool | Description |
|---|---|
| `list_sessions` | List all active Claude Code sessions |
| `create_session` | Create a new session (name, workingDir, optional command) |
| `delete_session` | Delete a session by id |
| `rename_session` | Rename a session |
| `send_input` | Send text input to a session |
| `get_output` | Get recent terminal output (configurable line count, default 50) |
| `open_in_terminal` | Open a session in a local terminal window (iTerm2 on macOS) |
| `get_server_info` | Get server connection info and status |

A separate Qhorus MCP endpoint at `POST /qhorus` exposes 40+ agent mesh tools (channel messaging, shared data, instance management, commitments, reactions, topics).

### REST API

#### Session Management (`/api/sessions`)

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/sessions` | List sessions (supports `?caseId=` filter, `?local=true` to skip federation) |
| `GET` | `/api/sessions/{id}` | Get session by id |
| `POST` | `/api/sessions` | Create session (supports `?overwrite=true` to replace existing) |
| `DELETE` | `/api/sessions/{id}` | Delete session |
| `PATCH` | `/api/sessions/{id}/rename?name=` | Rename session |
| `POST` | `/api/sessions/{id}/input` | Send input text to session |
| `GET` | `/api/sessions/{id}/output?lines=` | Get terminal output (default 50 lines) |
| `POST` | `/api/sessions/{id}/resize?cols=&rows=` | Resize terminal |
| `POST` | `/api/sessions/{id}/open-terminal` | Open in local terminal (iTerm2) |
| `GET` | `/api/sessions/{id}/lineage` | Worker lineage query (CaseHub) |
| `GET` | `/api/sessions/{id}/case-events` | SSE stream of case lifecycle events |
| `GET` | `/api/sessions/{id}/git-status` | Git/PR/CI status for session working dir |
| `GET` | `/api/sessions/{id}/service-health` | TCP port scan for common dev server ports |

#### Mesh / Channel Observation (`/api/mesh`)

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/mesh/config` | Mesh config (refresh strategy, interval, cursor staleness, actorId) |
| `GET` | `/api/mesh/channels` | List all Qhorus channels |
| `GET` | `/api/mesh/instances` | List all Qhorus instances |
| `GET` | `/api/mesh/channels/{name}/timeline?after=&limit=` | Channel message timeline (cursor-based pagination) |
| `GET` | `/api/mesh/feed?limit=` | Cross-channel message feed |
| `GET` | `/api/mesh/events?after=` | SSE stream of mesh-wide state (channels + instances + feed) |
| `GET` | `/api/mesh/channels/{name}/events?after=` | SSE stream of per-channel messages |
| `POST` | `/api/mesh/channels/{name}/messages` | Post a human message to a channel |
| `GET` | `/api/mesh/channels/{name}/commitments` | List commitments for a channel |
| `POST` | `/api/mesh/channels/{name}/reactions/batch` | Get reactions for a batch of message IDs |
| `POST` | `/api/mesh/channels/{name}/messages/{id}/reactions` | Add a reaction (emoji) |
| `DELETE` | `/api/mesh/channels/{name}/messages/{id}/reactions?emoji=` | Remove a reaction |
| `GET` | `/api/mesh/channels/{name}/topics` | List topics for a channel |
| `GET` | `/api/mesh/channels/{name}/members` | List channel members |
| `POST` | `/api/mesh/channels/{name}/members` | Join a channel |
| `DELETE` | `/api/mesh/channels/{name}/members` | Leave a channel |
| `GET` | `/api/mesh/channels/{name}/presence` | Presence list for a channel |

#### Channel CRUD (Qhorus auto-mounted)

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/channels` | List channels (served by Qhorus `ChannelResource`) |
| `POST` | `/api/channels` | Create a channel (served by Qhorus `ChannelResource`) |
| `DELETE` | `/api/channels/{id}` | Delete a channel |

#### Fleet (`/api/peers`)

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/peers` | List fleet peers |
| `POST` | `/api/peers` | Add a peer |
| `PATCH` | `/api/peers/{id}` | Update peer |
| `DELETE` | `/api/peers/{id}` | Remove peer |
| `POST` | `/api/peers/{id}/ping` | Ping a peer |
| `GET` | `/api/peers/{id}/sessions` | Get sessions from a specific peer |
| `POST` | `/api/peers/generate-fleet-key` | Generate a fleet key |

#### WebSocket

| Endpoint | Purpose |
|---|---|
| `WS /ws/{session-id}` | Terminal WebSocket (xterm.js) |

### Authentication

- **Browser:** WebAuthn passkeys via `quarkus-security-webauthn` (Touch ID, Face ID, iCloud Keychain sync)
- **Agent to Server:** `X-Api-Key` header (auto-generated on first run, saved to `~/.claudony/api-key`)
- **Fleet peer-to-peer:** Fleet key shared across nodes (`claudony.fleet-key` or `POST /api/peers/generate-fleet-key`)
- **Rate limiting:** Sliding 5-minute window (max 10 attempts/IP) on WebAuthn and registration paths
- **Invite flow:** First user bootstraps without token; subsequent users require a one-time UUID invite token (24h TTL)

### Protected Routes

| Path | Protection |
|---|---|
| `/api/**` | `@Authenticated` (session cookie or API key) |
| `/ws/**` | Session cookie checked in `@OnOpen` |
| `/app/**` | `@Authenticated` -- 302 to `/auth/login` if unauthenticated |
| `/auth/**`, `/q/**` | Public |

---

## CaseHub Integration (Optional)

Enabled via `claudony.casehub.enabled=true`. Implements all casehub-engine worker provisioner and execution SPIs:

| SPI Implementation | Engine SPI | Purpose |
|---|---|---|
| `ClaudonyWorkerProvisioner` | `WorkerProvisioner` | Creates tmux sessions running Claude CLI; stamps `@casehub_case_id` and `@casehub_role` as tmux session options for crash recovery |
| `ClaudonyWorkerExecutionManager` | `WorkerExecutionManager` | Virtual thread watcher per worker session; polls `tmux has-session`; atomic `registry.remove()` gate for publish-once |
| `ClaudonyCaseChannelProvider` | `CaseChannelProvider` | Creates Qhorus channels per case/purpose; `postToChannel` with `correlationId` and `deadline` support |
| `ClaudonyWorkerContextProvider` | `WorkerContextProvider` | Builds startup prompt from ledger lineage, mesh channels, and prior workers |
| `ClaudonyWorkerStatusListener` | `WorkerStatusListener` | Maps tmux lifecycle events to CaseHub worker states |
| `ClaudonyInstanceActorIdProvider` | `InstanceActorIdProvider` | Maps `claudony-worker-{uuid}` to `claude:{roleName}@v1` actor IDs for the audit ledger |
| `ClaudonyLedgerEventCapture` | (replaces excluded casehub-ledger bean) | Writes `CaseLedgerEntry` rows directly; drains `pendingExitSignals` on `WorkerExecutionCompleted` to fire case completion signal |

### Agent Mesh Framework

Claudony is the normative reference implementation of the CaseHub agent mesh. Channel layout and participation level are configurable:

| Config | Options | Default |
|---|---|---|
| `claudony.casehub.channel-layout` | `normative` (work/observe/oversight), `simple` (work/observe) | `normative` |
| `claudony.casehub.mesh-participation` | `active`, `reactive`, `silent` | `active` |

### Channel Namespace Conventions

Channels are a universal communication primitive -- not case-specific. Namespace prefix ownership:

| Prefix | Owner | Use |
|---|---|---|
| `case-{uuid}/` | casehub-engine | Case-scoped channels (work/observe/oversight) |
| `life/` | Household | Household and personal coordination |
| `team/` | General | General-purpose team chat rooms |
| `issue/` | Issue tracking | Issue-scoped discussions |
| `collab/` | Collaboration | Collaboration workspaces |

### Worker Provisioner Configuration

Per-agent configuration via `application.properties`:

```properties
claudony.casehub.enabled=true
claudony.casehub.workers.default-command=claude
claudony.casehub.workers.default-working-dir=~/claudony-workspace
claudony.casehub.workers.provider-config.code-reviewer.command=claude
claudony.casehub.workers.provider-config.code-reviewer.model=opus
claudony.casehub.channel-layout=normative
claudony.casehub.mesh-participation=active
```

`CompositeProviderConfigSource` aggregates config from two sources in precedence order: `ProvisionerConfigRegistry` (from casehub-engine-api, runtime-configurable) takes priority; `application.properties` `@ConfigMapping` serves as fallback.

---

## Session Expiry

Idle sessions are cleaned up by `SessionIdleScheduler` (`@Scheduled every 5m`). Three pluggable expiry policies:

| Policy | Name | Mechanism |
|---|---|---|
| `UserInteractionExpiryPolicy` | `user-interaction` | Checks `session.lastActive()` (default) |
| `TerminalOutputExpiryPolicy` | `terminal-output` | Checks `tmux display-message #{window_activity}` |
| `StatusAwareExpiryPolicy` | `status-aware` | Never expires sessions with a non-shell foreground process |

Global default: `claudony.session-expiry-policy=user-interaction`. Per-session override via `CreateSessionRequest.expiryPolicy`.

---

## Deployment

### Ports

| Mode | Default Port |
|---|---|
| Server | 7777 |
| Agent | 7778 |

### Running

```bash
# Dev mode (hot reload)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -Dclaudony.mode=server

# JVM jar
java -Dclaudony.mode=server -Dclaudony.bind=0.0.0.0 -jar target/quarkus-app/quarkus-run.jar

# Native binary
./target/claudony-1.0.0-SNAPSHOT-runner

# Two-node fleet via Docker Compose
export CLAUDONY_FLEET_KEY=$(openssl rand -base64 32)
docker compose up
```

### Key Configuration

```properties
claudony.mode=server|agent
claudony.port=7777
claudony.bind=localhost              # 0.0.0.0 for remote access
claudony.server.url=http://localhost:7777
claudony.claude-command=claude
claudony.default-working-dir=~/claudony-workspace
claudony.peers=                      # comma-separated peer URLs for fleet
```

### Persistence

PostgreSQL required for all environments. Qhorus data (channels, messages, ledger) lives in a named `qhorus` datasource. Dev Services provides a container automatically in dev/test. Production requires an explicit URL:

```properties
%prod.quarkus.datasource.qhorus.reactive.url=postgresql://localhost:5432/claudony_qhorus
%prod.quarkus.datasource.qhorus.jdbc.url=jdbc:postgresql://localhost:5432/claudony_qhorus
```

### Directory Convention

| Path | Purpose |
|---|---|
| `~/.claudony/` | System state -- credentials, API key, encryption key (`rw-------`) |
| `~/claudony-workspace/` | Default session working directory (visible, user-facing) |

---

## Dependencies

| Repo | How |
|---|---|
| `casehub-qhorus` | Embedded directly; named `qhorus` datasource |
| `casehub-qhorus-postgres-broadcaster` | PostgreSQL LISTEN/NOTIFY cross-instance event fan-out |
| `casehub-engine` | Implements its worker provisioner SPIs; `@WorkerBackend` qualifier |
| `casehub-ledger` | Transitively via Qhorus; `CaseLedgerEntry` JPA entity used directly |
| `casehub-platform-api` | Platform preferences (`ChannelCursorStaleness` preference key) |
| `casehub-worker-api` | Worker model types |
| `casehub-engine-api` | `CaseChannelLayout`, `MeshParticipationStrategy` SPIs; `ProvisionerConfigRegistry` |

---

## Technology Stack

| Component | Technology |
|---|---|
| Runtime | Java 21 API (compiled on Java 26 JVM) |
| Framework | Quarkus 3.32.2 |
| Native image | GraalVM 25 |
| Terminal multiplexer | tmux |
| Terminal emulator (browser) | xterm.js |
| Frontend | TypeScript, Lit 3, Quinoa + esbuild |
| UI components | `@casehubio/blocks-ui-channel-activity`, `@casehubio/pages-ui-tokens` |
| Auth | quarkus-security-webauthn + custom ApiKeyAuthMechanism |
| MCP | quarkus-mcp-server-http (1.11.1) |
| Build | Maven (`mvn`, not `./mvnw`) |
| TLS (deployment) | Caddy reverse proxy |

---

## Browser Dashboard

Two pages served by Quarkus:

- **`/app/index.html`** -- Fleet home: session panel (grid/table view toggle), fleet panel, mesh panel
- **`/app/session.html`** -- Terminal page: xterm.js terminal with compose overlay; case-bound sessions get the full workbench (channel nav, feed, input, task/correlation/artifact panels)

The workbench (`claudony-workbench.ts`) is dynamically loaded when a session has a `caseId`. It composes `<channel-feed>` and `<channel-input>` from `@casehubio/blocks-ui-channel-activity` and owns SSE lifecycle, cursor persistence, case context, and commitments.

### Responsive Design

Responsive layouts support tablet and phone form factors (#179). Session grid adapts column count; panels collapse and expand based on viewport width.

---

## What This Repo Does NOT Do

- Define orchestration rules (that is casehub-engine)
- Define agent messaging protocols (that is casehub-qhorus)
- Own audit ledger logic (that is casehub-ledger)
- Manage human task inboxes (that is casehub-work)
- Reimplement channel, message, or commitment logic -- Qhorus handles all of that

The tmux session layer is deliberately kept free of CaseHub/Qhorus concepts. The CaseHub wiring lives in `claudony-casehub` as a clean SPI implementation layer.

---

## Design Documents

- `ARC42STORIES.MD` (project root) -- primary architecture record (Arc42Stories format, 3 Journeys, 9 Chapters, 9 Layer entries)
- `docs/DESIGN.md` -- operational reference: component structure, data flows, key design decisions
- `docs/adr/` -- architectural decision records (ADR-0001 through ADR-0007+)
