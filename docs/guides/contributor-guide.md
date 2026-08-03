# claudony -- Contributor Guide

> Internal architecture, services, SPIs, and extension points for platform builders modifying Claudony.

**GitHub:** [casehubio/claudony](https://github.com/casehubio/claudony)

---

## Module Structure

| Module | Artifact | Purpose |
|---|---|---|
| `claudony-core` | `claudony-core` | Session lifecycle -- tmux session control, `SessionRegistry` (tenant-filtered), `ExpiryPolicy` SPI (3 implementations), `TenantContext` SPI with `DefaultTenantContext`, `WorkerCaseLifecycleEvent` CDI bridge |
| `claudony-casehub` | `claudony-casehub` | CaseHub SPI implementations -- provisioning, execution watching, channel management, context building, status mapping, ledger event capture, mesh framework (identity bridge, system prompt, causal link resolution) |
| `claudony-app` | `claudony` (runnable) | Quarkus application: authentication, session API, WebSocket streaming, MCP server, fleet management, channel observation, browser dashboard (Quinoa + esbuild + Lit 3) |

---

## Internal Architecture

### Layer Taxonomy

Claudony's architecture is layered. Each layer builds on the one below; the dependency direction is strictly downward:

| Layer | Concern | Key Classes |
|---|---|---|
| L1 Core Session Engine | tmux lifecycle, `SessionRegistry`, `TmuxService`, WebSocket/FIFO streaming, REST `/api/sessions` | `TmuxService`, `SessionRegistry`, `SessionResource`, `TerminalWebSocket`, `ServerStartup` |
| L2 Auth + Session Lifecycle | WebAuthn passkey, API key, `EncryptionKeyConfigSource`, `ExpiryPolicy` SPI, rate limiting, invite flow | `ApiKeyAuthMechanism`, `CredentialStore`, `AuthRateLimiter`, `ExpiryPolicyRegistry` |
| L3 Fleet / Peer Mesh | `PeerRegistry`, peer discovery (static/manual/mDNS), session federation, fleet key auth | `PeerRegistry`, `PeerClient`, `StaticConfigDiscovery`, `ProxyWebSocket` |
| L4 Agent Mode + MCP | `ClaudonyMcpTools` (8 tools, JSON-RPC `POST /mcp`), `ServerClient`, iTerm2 integration | `ClaudonyMcpTools`, `ServerClient`, `ITerm2Adapter` |
| L5 Qhorus Integration | Named datasource (`qhorus`), `ClaudonyChannelBackend`, `ChannelEventBus`, `MeshResource` | `ClaudonyChannelBackend`, `ChannelEventBus`, `MeshResource`, `CasehubResource` |
| L6 CaseHub SPI Foundation | `ClaudonyWorkerProvisioner`, `ClaudonyCaseChannelProvider`, `ClaudonyWorkerContextProvider`, `ClaudonyWorkerStatusListener`, `CaseLineageQuery`, `ClaudonyLedgerEventCapture`, `ProviderConfigSource`, `CompositeProviderConfigSource`, `ClaudonyProviderConfig`, `WorkerCommandBuilder`, `QhorusCausalLinkResolver` | All in `claudony-casehub` |
| L7 Agent Mesh Framework | `WorkerSessionMapping`, `ClaudonyInstanceActorIdProvider`, `MeshSystemPromptTemplate` | Identity bridge and system prompt assembly |
| L8 Case Worker Panel | `CaseEventBroadcaster`, `CaseWorkerUpdateStrategy` SPI, `CasehubStartupService` | SSE fan-out, pluggable update strategies |
| L9 Production Orchestration | `ClaudonyWorkerExecutionManager`, `AgentCase`, signal drain, crash recovery | Virtual thread watcher, case goal evaluation |

---

### Core (`claudony-core`)

#### TmuxService

All tmux interaction goes through `TmuxService` (ProcessBuilder wrappers). Two session creation paths:

- **`createSession(name, workingDir, command)`** -- user sessions. Launches a shell, keeps it alive after the command exits. Used for interactive terminal sessions.
- **`createWorkerSession(name, workingDir, command)`** -- CaseHub worker sessions. Runs `tmux new-session ... "sh -c <command>"` with `remain-on-exit off`. The session closes when the command exits, which is what the exit watcher needs to detect.

Additional operations: `pipePaneToFifo()`, `captureHistory()`, `sendKeys()` (literal mode with `-l` flag), `resizeWindow()` (not `resize-pane` -- works for detached sessions), `killSession()`, `listSessions()`, `setSessionOption()`, `getSessionOption()`, `displayMessage()`.

#### SessionRegistry

In-memory `ConcurrentHashMap` of active sessions. Tenant-filtered by default -- `all()`, `find()`, `findByCaseId()` filter by `TenantContext.currentTenantId()`. Unscoped variants (`allUnscoped()`, `findUnscoped()`, `existsByName()`) exist for system operations (bootstrapping, fleet federation).

- `touch(id)` bumps `lastActive` timestamp (called from WebSocket open, input, REST input)
- `findByCaseId(caseId)` returns workers ordered by `createdAt`
- `register(session)` / `remove(id)` -- the atomic `remove()` is the publish gate for worker exit events

#### TenantContext SPI

Interface: `TenantContext.currentTenantId()`. Default implementation `DefaultTenantContext` (`@ApplicationScoped`) delegates to `CurrentPrincipal.tenancyId()` when request scope is active, falls back to `TenancyConstants.DEFAULT_TENANT_ID` outside request context.

#### ExpiryPolicy SPI

Interface: `ExpiryPolicy` with `name()` and `shouldExpire(Session, TmuxService)`. Three implementations:

| Implementation | Name | Mechanism |
|---|---|---|
| `UserInteractionExpiryPolicy` | `user-interaction` | `session.lastActive()` check (default) |
| `TerminalOutputExpiryPolicy` | `terminal-output` | `tmux display-message #{window_activity}` |
| `StatusAwareExpiryPolicy` | `status-aware` | Never expires sessions with a non-shell foreground process |

`ExpiryPolicyRegistry` auto-discovers all `ExpiryPolicy` implementations via `@Any Instance<ExpiryPolicy>`. `SessionIdleScheduler` runs every 5 minutes.

---

### CaseHub SPI Implementations (`claudony-casehub`)

All class names dropped the `Reactive` prefix as of #184 (blocking API migration, 2026-07-27).

#### ClaudonyWorkerProvisioner (`WorkerProvisioner`)

Creates a tmux worker session running the Claude CLI. The `provision()` method:

1. `setupSession()` -- resolves `ClaudonyProviderConfig` via `ProviderConfigSource.forAgent(roleName)`, builds enriched CLI command via `WorkerCommandBuilder.build()`, calls `TmuxService.createWorkerSession()`, stamps tmux session options (`@casehub_case_id`, `@casehub_role`, `@casehub_tenant_id`) for crash recovery, registers session in `SessionRegistry` and `WorkerSessionMapping`
2. `QhorusCausalLinkResolver.resolve()` -- looks up the triggering message's `CaseLedgerEntry` UUID to establish the W3C PROV-DM causal chain. Stores result in `causalContext: ConcurrentHashMap<CausalKey, UUID>`, drained by `ClaudonyLedgerEventCapture` on `WorkerStarted`
3. `signalStarted()` -- fires `CaseHubRuntime.signal(caseId, "workers.<role>.started", true)`
4. `startWatcher()` -- delegates to `ClaudonyWorkerExecutionManager.startWatcherForSession()`

`terminate()` removes from registry before killing the tmux session -- `registry.remove()` clears the session to prevent the watcher from also publishing a completion event.

`getCapabilities()` returns `providerConfigSource.declaredAgentIds()` -- the union of agents declared in both the registry and config mapping.

#### ClaudonyWorkerExecutionManager (`WorkerExecutionManager`)

Starts a virtual thread watcher per worker session. The watcher polls `tmux has-session` at a configurable interval (`claudony.casehub.worker-exit-poll-ms`, default 5000). On exit detection:

1. `pendingExitSignals.put(caseId, roleName)` -- stores the signal **before** publishing
2. `registry.remove(sessionId) != null` -- atomic gate; whichever caller wins (watcher or `terminate()`) publishes `WorkflowExecutionCompleted`
3. `eventBus.send("casehub.worker.completed", event)`

`drainExitSignal(UUID caseId)` is consumed by `ClaudonyLedgerEventCapture` on `WorkerExecutionCompleted` to fire the case context signal **after** `em.flush()` (ordering: PP-20260617-52285f).

`supports(String workerId)` returns true when the worker session exists in the registry.

#### ClaudonyCaseChannelProvider (`CaseChannelProvider`)

Creates Qhorus channels per case/purpose. Init-on-first-touch cache with `CountDownLatch` race guard (#120). Channel names follow the pattern `case-{caseId}/{purpose}` where purpose comes from the `CaseChannelLayout` SPI.

- `openChannels(caseId, definition)` -- calls `CaseChannelLayout.named(config.channelLayout())` to get the channel spec list, creates channels via `ChannelService.create()`, fires `CaseChannelCreatedEvent` CDI event
- `postToChannel(caseId, channelName, message)` -- extracts `correlationId` for COMMAND/QUERY messages, posts via `channelService`

#### ClaudonyWorkerContextProvider (`WorkerContextProvider`)

Builds the Claude startup prompt from lineage and channel context:

1. Queries `CaseLineageQuery.findCompletedWorkers(caseId)` for prior workers
2. Queries open channels via `ClaudonyCaseChannelProvider`
3. Generates system prompt via `MeshSystemPromptTemplate.generate()` based on `MeshParticipation` level
4. Stores result in `WorkerContext.properties()` as `systemPrompt` and `meshParticipation`

Uses `Context.isOnEventLoopThread()` guard on `@WithSession` paths -- `isEventLoopContext()` incorrectly returns true for `executeBlocking` workers (PP-20260620-cb7137).

#### ClaudonyWorkerStatusListener (`WorkerStatusListener`)

Maps tmux lifecycle events to `SessionRegistry` status transitions. Fires `WorkerStalledEvent` CDI event on stall detection.

#### ClaudonyLedgerEventCapture

Replaces the excluded casehub-ledger `CaseLedgerEventCapture` bean. Observes `CaseLifecycleEvent` asynchronously (`@ObservesAsync`):

- Writes `CaseLedgerEntry` rows directly via `@LedgerPersistenceUnit` EntityManager
- Maintains a per-case sequence counter for entry ordering
- On `WorkerStarted`: drains `causalContext` from the provisioner, sets `causedByEntryId` on the ledger entry
- On `WorkerExecutionCompleted`: drains `pendingExitSignals` from `ClaudonyWorkerExecutionManager`, calls `CaseHubRuntime.signal("workers.<role>.exited", true)` **after** `em.flush()` (signal drain ordering: PP-20260617-52285f)
- Propagates `tenancyId` from the event; defaults to `"default"` when null (#143)

#### QhorusCausalLinkResolver

`@ApplicationScoped`. Resolves `causedByEntryId` for each provisioned worker by looking up the triggering Qhorus message's `CaseLedgerEntry`. Called by the provisioner during `provision()`. Result stored in the provisioner's `causalContext` map and drained by `ClaudonyLedgerEventCapture` on `WorkerStarted`.

#### ProviderConfigSource SPI

Interface for per-agent config lookup by agentId. Returns `ClaudonyProviderConfig` (record with optional command, model, tools, systemPrompt, appendSystemPrompt, workingDir).

`CompositeProviderConfigSource` (`@ApplicationScoped`) aggregates two sources in precedence order:
1. `ProvisionerConfigRegistry` from casehub-engine-api (runtime-configurable)
2. `@ConfigMapping` fallback (`application.properties`)

`declaredAgentIds()` returns the union of agent IDs from both sources.

#### WorkerCommandBuilder

Static utility. `build(baseCommand, config, dynamicAppendPrompt)` constructs the enriched Claude CLI command:

- `--model` from `ClaudonyProviderConfig.model()`
- `--system-prompt` from `ClaudonyProviderConfig.systemPrompt()`
- `--append-system-prompt` via `mergeAppendPrompts()` -- combines static operator prompt (from config) with dynamic mesh prompt (from `MeshSystemPromptTemplate`)
- Shell-safe quoting for all values

`--system-prompt` and `--append-system-prompt` coexist (not mutually exclusive).

---

### Agent Mesh Framework

#### WorkerSessionMapping

Bridges CaseHub role names to Claudony tmux session UUIDs. Three maps:

- `caseId:role` -> `sessionId` (precise lookup when caseId is known)
- `role` -> `sessionId` (fallback for callers without caseId)
- `sessionId` -> `roleName` (reverse lookup for `InstanceActorIdProvider`)

Re-registration cleans up stale reverse entries. Same-role workers across different cases use the precise `caseId:role` key.

#### ClaudonyInstanceActorIdProvider

`@Alternative @Priority(1)` implementation of Qhorus's `InstanceActorIdProvider` SPI. Strips the `claudony-worker-` prefix from tmux session names, looks up the role name via `WorkerSessionMapping`, returns `claude:{roleName}@v1`. Falls back to raw instanceId for unknown or terminated sessions.

#### MeshSystemPromptTemplate

Package-private. Generates structured system prompts based on `MeshParticipation` level:

- **ACTIVE** -- full template: case header, ROLE, MESH CHANNELS (from `CaseChannelLayout`), STARTUP sequence, PRIOR WORKERS (from `CaseLineageQuery`), MESSAGE DISCIPLINE
- **REACTIVE** -- reduced: no startup sequence or periodic check_messages
- **SILENT** -- returns empty (no prompt delivered)

Assembly path: `ClaudonyWorkerContextProvider.buildContext()` -> queries lineage + channels -> `MeshSystemPromptTemplate.generate()` -> stores in `WorkerContext.properties["systemPrompt"]` -> provisioner passes as `dynamicAppendPrompt` to `WorkerCommandBuilder.build()`.

#### CaseChannelLayout SPI (in casehub-engine-api)

Defines which channels open when a case starts. Implementations:

- `NormativeChannelLayout` (default): 3-channel pattern -- work (all types), observe (EVENT only), oversight (QUERY+COMMAND)
- `SimpleLayout`: 2-channel -- work + observe only (no human oversight)

Selected via `CaseChannelLayout.named(config.channelLayout())` factory method.

#### MeshParticipationStrategy SPI (in casehub-engine-api)

Defines how a worker engages with the mesh. Implementations:

- `ActiveParticipationStrategy` (default): register, announce, check messages periodically
- `ReactiveParticipationStrategy`: no registration; engage only when addressed
- `SilentParticipationStrategy`: no mesh participation

Selected via `MeshParticipationStrategy.named(config.meshParticipation())` factory method.

#### Normative Channel Semantics

| Channel suffix | Semantics | Allowed speech acts |
|---|---|---|
| `/work` | Task assignment and completion | COMMAND, RESPONSE, DONE, DECLINE |
| `/observe` | Passive state broadcast | EVENT, INFORM |
| `/oversight` | Human governance gate | COMMAND (to human), RESPONSE (from human) |

`allowedTypes` on each `Channel` enforces this at the Qhorus layer -- messages outside the declared types are rejected.

---

### Application (`claudony-app`)

#### Terminal Streaming (No PTY)

tmux does not expose a PTY to the Quarkus process. Streaming uses:

- **Output:** `tmux pipe-pane -o` -> named FIFO -> Java virtual thread -> WebSocket
- **Input:** `tmux send-keys -t name -l "text"` (literal mode, `-l` flag required)
- **History on reconnect:** `tmux resize-window` (delivers SIGWINCH to TUI apps) -> 150ms delay -> `tmux capture-pane -e -p -S -100` -> strip padding/leading/trailing blank rows -> append `ESC[row;colH` cursor position -> send synchronously **before** starting pipe-pane (eliminates race condition)

History processing rules:
- Leading and trailing blank rows (scrollback/pane padding) stripped
- Blank rows within content **preserved** (removing shifts cursor positioning)
- Visually blank rows with only ANSI codes stored as empty strings
- Cursor-position escape derived from `tmux display-message #{cursor_y} #{cursor_x}`

#### Channel Architecture

Channels are universal -- not case-specific. Key design:

- Qhorus owns channel CRUD (`ChannelResource` auto-mounted via JAX-RS classpath scanning)
- `ClaudonyChannelBackend` (`HumanObserverChannelBackend` SPI) registers for ALL channels (no prefix filter)
- Backend registration is lazy (at first `EventSource` subscribe) to avoid circular module dependency (ADR-0006)
- `ServerStartup.bootstrapChannelBackends()` re-registers on restart (idempotent)
- Auto-join on post; reaction SSE push; presence via `ChannelEventBus.subscriberCount()`
- SSE: 500ms server tick via `Multi.createFrom().ticks()` (true push deferred; ADR-0007)
- SSE emits bare JSON strings -- RESTEasy wraps `data: ...\n\n` automatically; manual `data:` prefix causes double-framing

#### Fleet Management

Symmetric peer mesh (no master). `PeerRegistry` maintains the authoritative peer list with circuit breaker (3 failures -> OPEN, exponential backoff 30s->5min). Session federation: `GET /api/sessions` fans out to healthy peers via virtual threads with `?local=true` guard to prevent recursive federation.

Three discovery mechanisms: `StaticConfigDiscovery` (config property), `ManualRegistrationDiscovery` (REST-triggered), `MdnsDiscovery` (scaffold). `ProxyWebSocket` proxies terminal sessions for peers behind NAT.

Cross-node channel events synchronize via PostgreSQL LISTEN/NOTIFY (`casehub-qhorus-postgres-broadcaster` SPI implementation).

#### Authentication Internals

- `EncryptionKeyConfigSource` -- MicroProfile `ConfigSource` (not CDI bean); generates 256-bit key on first boot; persists to `~/.claudony/encryption-key` with `chmod 600`. Declared in `META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource`.
- `ApiKeyService` -- key resolution priority: (1) config property, (2) `~/.claudony/api-key` file, (3) auto-generate. Both Server and Agent call `autoInit()` at startup.
- `AuthRateLimiter` -- Vert.x route handler registered via `@Observes Router` to cover WebAuthn ceremony paths (`/q/webauthn/*`) which bypass JAX-RS `ContainerRequestFilter`.
- `WebAuthnPatcher` -- swaps Vert.x `NoneAttestation` handler with `LenientNoneAttestation` at startup to handle iCloud Keychain passkeys (non-zero AAGUID with `fmt=none`).

#### MCP Server

Two MCP endpoints (separated in #105):

- `POST /mcp` -- 8 Claudony session management tools (`ClaudonyMcpTools`)
- `POST /qhorus` -- 40+ Qhorus agent mesh tools (named server `"qhorus"`, auto-registered via classpath)

Both use HTTP JSON-RPC (GraalVM-native compatible, no stdio subprocess). `quarkus.mcp.server.tools.page-size=0` disables 50-tool pagination cap.

#### CaseWorkerUpdateStrategy SPI

Pluggable update model for the case worker panel:

| Strategy | Class | Behaviour |
|---|---|---|
| `events-only` | `EventsOnlyStrategy` | Emits on `WorkerCaseLifecycleEvent` only |
| `hybrid` (default) | `HybridStrategy` | Events + configurable heartbeat (default 30s) |
| `registry-hooks` | `RegistryHooksStrategy` | Fires on any `SessionRegistry` mutation |

`CaseEventBroadcaster` fans out events via SSE keyed by caseId. `GET /api/sessions/{id}/case-events` is the SSE endpoint.

#### Frontend Architecture

TypeScript frontend built by Quinoa + esbuild. Two entry points:

- `app.ts` -- fleet home (session panel, fleet panel, mesh panel)
- `terminal.ts` -- terminal page (compose overlay, lifecycle, workbench)

Key components (all LitElement):

| Component | File | Purpose |
|---|---|---|
| `session-panel` | `session-panel.ts` | Session grid/table view toggle, git/PR status, create/delete dialogs |
| `claudony-workbench` | `claudony-workbench.ts` | Composition root for case-bound sessions; composes terminal + channel-nav + channel-feed + channel-input + task/correlation/artifact panels |
| `terminal-workspace` | `terminal-workspace.ts` | Three-column flex coordinator for fleet mode |
| `terminal-header` | `terminal-header.ts` | Back link, session name, status badge, toggles |
| `claudony-fleet-panel` | `claudony-fleet-panel.ts` | Fleet peer management |
| `claudony-mesh-panel` | `claudony-mesh-panel.ts` | Mesh overview/channel/feed views, SSE, interjection dock |
| `worker-panel` | `worker-panel.ts` | SSE worker list, click-to-switch |
| `channel-panel` | `channel-panel.ts` | Channel feed + input (superseded by workbench for case-bound sessions) |
| `key-bar` | `key-bar.ts` | Touch device special keys |

UI components consumed from `@casehubio/blocks-ui-channel-activity` (channel-feed, channel-input, channel-task-panel, channel-correlation-panel, channel-artifact-panel) and `@casehubio/pages-ui-tokens` (design tokens). Delivered via Maven SNAPSHOT artifacts (WebJar pattern).

`theme.ts` bridges `--pages-*` design tokens to claudony's legacy CSS variable names via `initTheme()`.

---

### Persistence Model

Three named persistence units:

| PU | Datasource | Content |
|---|---|---|
| `claudony` | default | Auth credentials, sessions (future -- currently in-memory `SessionRegistry`) |
| `qhorus` | `qhorus` (PostgreSQL) | Qhorus channels, messages, shared data, ledger entries -- all Flyway-managed |
| (engine) | (optional) | casehub-engine state when CaseHub is active |

`casehub-ledger` CDI beans (`CaseLedgerEntryRepository`, `CaseLedgerEventCapture`) are excluded via `quarkus.arc.exclude-types` to avoid `LedgerEntryRepository` ambiguity. Only `CaseLedgerEntry` (JPA entity) is used directly.

---

### Production Case Orchestration (L9)

#### AgentCase

Production CaseHub case definition. Extends `YamlCaseHub`; loads `casehub/agent.yaml`. Triggers on `.topic != null`; auto-completes when `workers.agent.exited == true` (set by signal drain on session exit).

#### CasehubStartupService

Iterates the session registry on startup. For sessions with `@casehub_case_id` and `@casehub_role` tmux options, resolves the `CaseInstance` and restarts exit watchers via `ClaudonyWorkerExecutionManager`. Handles UUID validation, null instances, and roleName fallback to "worker". Returns count of started watchers.

#### Signal Drain Pattern

The complete worker exit and case completion flow:

```
Claude CLI exits -> tmux session closes
  -> ClaudonyWorkerExecutionManager watcher: tmux has-session returns non-zero
  -> pendingExitSignals.put(caseId, roleName)
  -> registry.remove(sessionId) != null   <- atomic gate
  -> eventBus.send("casehub.worker.completed", WorkflowExecutionCompleted)

casehub-engine -> WorkerExecutionCompletedHandler -> ClaudonyLedgerEventCapture
  -> em.persist(CaseLedgerEntry)
  -> em.flush()                             <- ledger row must exist before signal
  -> drainExitSignal(caseId) -> roleName
  -> CaseHubRuntime.signal(caseId, "workers.<role>.exited", true)
  -> engine evaluates goal -> CaseStatus.COMPLETED
```

Ordering constraint: `pendingExitSignals.put()` before `eventBus.send()`, `drainExitSignal()` after `em.flush()`. Documented in PP-20260617-52285f.

---

## CDI Patterns

### @DefaultBean CDI Displacement

SPI default beans (e.g., `EmptyCaseLineageQuery`) carry `@DefaultBean` and are displaced at deployment by `@Alternative @Priority(1)` implementations when the full engine stack is present. No registration code needed.

### CDI Exclusions

`quarkus.arc.exclude-types` removes conflicting beans from the classpath:
- `casehub-ledger` CDI beans (`CaseLedgerEntryRepository`, `CaseLedgerEventCapture`) -- prevents `LedgerEntryRepository` ambiguity
- Various engine-internal beans that inject unsatisfied dependencies in Claudony's context
- `QuartzRetryService` (engine addition that requires unavailable dependencies)

### @WorkerBackend Qualifier

`ClaudonyWorkerExecutionManager` carries `@WorkerBackend` (from `casehub-engine-common`) to distinguish it from the engine's `CompositeWorkerExecutionManager`. The provisioner injects it via `@WorkerBackend ClaudonyWorkerExecutionManager`.

---

## Dependencies

### Depends On

| Repo | How |
|---|---|
| `casehub-qhorus` | Embedded directly; named `qhorus` datasource; `ChannelService`, `QhorusDashboardService`, all store SPIs |
| `casehub-qhorus-postgres-broadcaster` | `ChannelActivityBroadcaster` SPI impl; PostgreSQL LISTEN/NOTIFY cross-node sync |
| `casehub-engine` | Implements its worker SPIs; `@WorkerBackend` qualifier |
| `casehub-engine-api` | `CaseChannelLayout`, `MeshParticipationStrategy` SPIs; `ProvisionerConfigRegistry` |
| `casehub-worker-api` | Worker model types |
| `casehub-ledger` | `CaseLedgerEntry` JPA entity used directly (CDI beans excluded) |
| `casehub-platform-api` | `PreferenceProvider`, `ChannelCursorStaleness` preference key, `TenancyConstants` |
| `casehub-platform-config` | Platform config binding |

### Depended On By

Nothing -- Claudony is the integration terminus.

---

## Testing

### Test Count

As of 2026-08-01: 16 in `claudony-core` + 175 in `claudony-casehub` + ~420 in `claudony-app` = **~611 total Java tests**. Plus 25 vitest frontend tests and 4+ E2E workbench tests (Playwright). Docker required (PostgreSQL Dev Services).

### Test Layers

- **Unit tests** -- plain JUnit, no Quarkus container; stateful beans use `resetForTest()` + `@AfterEach`
- **Integration tests** (`@QuarkusTest`) -- full Quarkus context; Qhorus uses `InMemory*Store` implementations (from `casehub-qhorus-testing`)
- **E2E tests** -- Playwright browser tests + real tmux tests; assert tmux session state, not LLM output (non-deterministic)
- **Engine integration** (`CasehubEnabledProfile`) -- tests that exercise the engine round-trip use `@TestProfile(CasehubEnabledProfile.class)` with `claudony.casehub.enabled=true`

### Build Commands

```bash
# All Java tests
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test

# Frontend tests
npm --prefix app/src/main/webui test

# E2E tests (requires Chromium + claude CLI)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Pe2e
```

---

## Key Architectural Constraints

1. **`TmuxService`, `SessionRegistry`, `TerminalWebSocket` must stay clean of CaseHub/Qhorus concepts.** SPI implementations in `claudony-casehub` are the sole coupling point.
2. **HTTP JSON-RPC for MCP is non-negotiable.** Native image requires no stdio subprocess.
3. **Named datasource `qhorus` isolates Qhorus schema.** All Flyway migrations are Qhorus-managed.
4. **WebAuthn requires HTTPS.** TLS is terminated by Caddy in production, not Quarkus.
5. **`@ConfigMapping` properties must not be added to `application.properties` until the library JAR is on the classpath.** SmallRye strict validation fires before library JAR config roots register.
6. **`@WithSession` requires event loop thread.** Use `Context.isOnEventLoopThread()` guard (not `isEventLoopContext()`).
7. **Signal drain ordering** (`pendingExitSignals.put()` before publish; drain after `em.flush()`) is a hard constraint documented in PP-20260617-52285f.

---

## Anti-Patterns (Documented in ARC42STORIES.MD)

1. **`@ConfigMapping` strict validation before library JAR registers** -- do not add `claudony.casehub.*` to base `application.properties`
2. **JPA repository class without CDI annotation** -- use SPI interface + `@DefaultBean` no-op + `@Alternative @Priority(1)` full impl
3. **tmux shell outlives command** -- `createWorkerSession()` uses `sh -c <command>` with `remain-on-exit off`
4. **`@Blocking` on plain CDI startup observer** -- remove `@Blocking`; startup observers run on the main thread
5. **`@RegisterProvider` on REST client interface not guaranteed for programmatic builders** -- call `.register()` explicitly on every `RestClientBuilder`

---

## Current State

- All 9 chapters complete (J1 Remote Terminal Access, J2 MCP Agent, J3 CaseHub Integration)
- ~611 Java tests passing; 25 vitest; 4+ E2E workbench
- Conversation maturity: general-purpose channels, reactions, member/presence panels, responsive layouts (#177, #178, #190, #191, #192, #179)
- Multi-tenancy foundation: `TenantContext` SPI, tenant-filtered `SessionRegistry`, scoped/unscoped query paths
- Dual MCP endpoints: `/mcp` (8 session tools), `/qhorus` (40+ agent mesh tools)
- Frontend: Quinoa + esbuild + Lit 3; blocks-ui and pages-ui components via Maven SNAPSHOT
- ADR-0005: CaseHub integration is optional -- Claudony works as a standalone session manager

---

## Design Documents

- `ARC42STORIES.MD` (project root) -- primary architecture record (Arc42Stories format)
- `docs/DESIGN.md` -- operational reference: component structure, data flows, key decisions
- `docs/adr/` -- architectural decision records
