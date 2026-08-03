# claudony — Contributor Guide

> Internal architecture, services, SPIs, and extension points for platform builders modifying Claudony.

**GitHub:** [casehubio/claudony](https://github.com/casehubio/claudony)

---

## Module Structure

| Module | Purpose |
|---|---|
| `claudony-core` | Session lifecycle management — tmux session control, registry and expiry policy SPI, `TenantContext` SPI with tenant-filtered `SessionRegistry` |
| `claudony-casehub` | Implements the casehub-engine worker provisioner SPIs — provisioning, execution watching, channel management, context building, status mapping, ledger event capture |
| `claudony-app` | Quarkus application: authentication, session API, WebSocket streaming, MCP server, fleet management, browser dashboard, TypeScript frontend (Quinoa + esbuild) |

---

## Internal Architecture

### Core (`claudony-core`)

Manages tmux session lifecycle: starting, stopping, and expiring sessions. A pluggable expiry policy SPI controls when sessions are considered idle. On restart, the registry is repopulated from live tmux sessions — tmux is the source of truth, independent of the Quarkus process.

`TenantContext` SPI (`currentTenantId()`) with `DefaultTenantContext @ApplicationScoped` — delegates to `CurrentPrincipal.tenancyId()` when request scope is active, falls back to `TenancyConstants.DEFAULT_TENANT_ID` outside request context. `SessionRegistry` filters `all()`/`find()`/`findByCaseId()` by tenant unconditionally; `allUnscoped()`/`findUnscoped()`/`existsByName()` for system operations.

### CaseHub SPI Implementations (`claudony-casehub`)

- **`ClaudonyReactiveWorkerProvisioner`** (`WorkerProvisioner`) — creates a tmux session running the Claude CLI. `provision()` uses `Uni.combine()` to run `setupSession()` (blocking tmux IO, worker pool) and `QhorusCausalLinkResolver.resolve()` (reactive Qhorus DB, event loop) concurrently.
- **`QhorusCausalLinkResolver`** (`@ApplicationScoped`) — resolves `causedByEntryId` for each provisioned worker by looking up the Qhorus `MessageLedgerEntry`. Result stored in `causalContext: ConcurrentHashMap<CausalKey, UUID>` in the provisioner; drained by `ClaudonyLedgerEventCapture` on `WorkerStarted` to set `CaseLedgerEntry.causedByEntryId`. Establishes the W3C PROV-DM causal chain.
- **`ClaudonyWorkerExecutionManager`** (`WorkerExecutionManager`) — virtual thread watcher; when a tmux session exits, stores `pendingExitSignals.put(caseId, roleName)` before publishing `WorkflowExecutionCompleted`; supports recovery after server restart via tmux session options.
- **`ClaudonyLedgerEventCapture`** — on `WorkerExecutionCompleted`, drains `pendingExitSignals` and calls `CaseHubRuntime.signal('workers.<role>.exited', true)`, patching case context and triggering goal evaluation.
- **`CasehubStartupService`** — iterates registry on startup and restarts exit watchers for in-flight workers after server restart.
- **`AgentCase`** — production CaseHub case definition; extends `YamlCaseHub`; triggers on `.topic != null`; auto-completes when `workers.agent.exited == true`.
- **`CaseChannelProvider`** — creates a Qhorus channel per case/purpose; `postToChannel` receives `correlationId` and `deadline` as first-class params.
- **`WorkerContextProvider`** — builds the Claude startup prompt from ledger lineage.
- **`WorkerStatusListener`** — maps tmux lifecycle events to CaseHub worker states.

### ProvisionerConfigRegistry SPI Infrastructure

Three-phase provisioner config model:
- `ProviderConfigSource` SPI — query interface for provisioner parameters (LLM provider, model, temperature, max tokens)
- `CompositeProviderConfigSource` — aggregates multiple sources, precedence-ordered (env vars -> tenant prefs -> system defaults)
- `WorkerContextProvider` — builds Claude startup prompt from ledger lineage + mesh system prompt (three-layer model: system + tenant + case)

### System Prompt Delivery — Three-Layer Prompt Model

1. **`MeshSystemPromptTemplate`** — generates structured prompt based on `MeshParticipation` (ACTIVE/REACTIVE/SILENT)
2. **`ClaudonyProviderConfig`** — per-agent `systemPrompt` (`--system-prompt` CLI flag) and `appendSystemPrompt` (`--append-system-prompt` CLI flag)
3. **`WorkerCommandBuilder.mergeAppendPrompts()`** — merges static `appendSystemPrompt` config with dynamic mesh prompt into final CLI arguments

Assembly path: `ClaudonyReactiveWorkerContextProvider.buildContext()` -> queries lineage + channels -> `MeshSystemPromptTemplate.generate()` -> stores in `WorkerContext.properties()` -> provisioner passes as `dynamicAppendPrompt` to `WorkerCommandBuilder.build()`.

### Application (`claudony-app`)

REST and WebSocket endpoints for session management and terminal streaming. WebAuthn passkey authentication for browser access; API key authentication for agent access. An MCP server exposes session management tools to a controller Claude instance. Fleet management handles multi-node peer discovery and health monitoring.

- `FleetMessageRelayObserver` — CLUSTER-scoped `MessageObserver` SPI implementation; relays channel-name ticks to all healthy fleet peers via `POST /api/internal/channels/notify`. Enables real-time SSE delivery across fleet nodes when Qhorus shares a PostgreSQL instance.
- `MeshResource` exposes the Qhorus mesh data to the dashboard via `QhorusDashboardService` — the correct consumer integration tier for dashboard/UI code (not `ReactiveQhorusMcpTools`, which is the MCP protocol dispatch layer for Claude Code).

### Terminal Streaming (No PTY)

tmux does not expose a PTY to the Quarkus process. Streaming uses:
- **Output:** `tmux pipe-pane` -> FIFO -> Java virtual thread -> WebSocket
- **Input:** `tmux send-keys` in literal mode
- **History on reconnect:** captured synchronously before starting pipe-pane to avoid race conditions

### Persistence Model

Three named persistence units: `claudony` (auth, sessions), `qhorus` (Qhorus message store), and an optional engine datasource when CaseHub is active.

### Channel Architecture

Channels are a universal communication primitive — not case-specific. Key design points:
- Qhorus owns channel CRUD (`ChannelResource` auto-mounted via JAX-RS classpath scanning)
- `ClaudonyChannelBackend` registers for ALL channels (no prefix filter)
- Auto-join on post; reaction SSE push; presence via `ChannelEventBus.subscriberCount()`
- Namespace conventions: `case-{uuid}/` (engine), `life/` (household), `team/` (general rooms), `issue/` (issue-scoped), `collab/` (collaboration)

### Agent Mesh Framework

Platform SPIs (defined in `casehub-engine-api`, `io.casehub.api.spi.mesh`):
- `CaseChannelLayout` — SPI declaring the channel topology for an agent case. Implementations: `NormativeChannelLayout` (work/observe/oversight), `SimpleLayout` (work/observe).
- `MeshParticipationStrategy` — SPI governing agent participation level (ACTIVE/REACTIVE/SILENT).

Normative channel layout (3-channel pattern):

| Channel suffix | Semantics | Agent speech acts |
|---|---|---|
| `/work` | Task assignment and completion | COMMAND, RESPONSE, DONE, DECLINE |
| `/observe` | Passive state broadcast | EVENT, INFORM |
| `/oversight` | Human governance gate | COMMAND (to human), RESPONSE (from human) |

`allowedTypes` on each `Channel` enforces this at the Qhorus layer — messages outside the declared types are rejected.

---

## Dependencies

### Depends On

| Repo | How |
|---|---|
| `casehub-qhorus` | Embedded directly; named `qhorus` datasource |
| `casehub-qhorus-postgres-broadcaster` | LISTEN/NOTIFY cross-instance event fan-out |
| `casehub-engine` | Implements its 4 worker provisioner SPIs; `@WorkerBackend` qualifier |
| `casehub-ledger` | Transitively via Qhorus (agent message ledger entries) and casehub-ledger |

### Depended On By

Nothing — Claudony is the integration terminus.

---

## Current State

- ~611 tests passing (16 in `claudony-core` + 175 in `claudony-casehub` + ~420 in `claudony-app` + integration); 25 vitest frontend tests; 4 E2E workbench tests
- Core complete: session management, WebSocket streaming, WebAuthn, fleet, CaseHub SPI wiring
- ADR-0005: CaseHub integration is optional — Claudony works as a standalone session manager without CaseHub
- Pages/Quinoa adoption: browser dashboard via casehub-pages DSL (`page()`, `tabs()`, `table()`, `metric()` primitives)
- Multi-tenancy foundation: `TenantContext` SPI, tenant-filtered `SessionRegistry`, scoped/unscoped query paths
- Dual MCP endpoints: Claudony session tools at `/mcp` (8 tools), Qhorus agent mesh tools at `/qhorus` (40+ tools)

---

## Design Documents

- [docs/DESIGN.md](https://raw.githubusercontent.com/casehubio/claudony/main/docs/DESIGN.md) — integration architecture, CaseHub SPI implementations, three-panel dashboard plan
- [adr/INDEX.md](https://raw.githubusercontent.com/casehubio/claudony/main/adr/INDEX.md) — architectural decision records
- [ARC42STORIES.MD](https://raw.githubusercontent.com/casehubio/claudony/main/ARC42STORIES.MD) — primary architecture record (Arc42Stories format)
