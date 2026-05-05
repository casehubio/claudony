# claudony-casehub — CaseHub SPI Implementations

> *Claudony provisions Claude (and other LLM) workers for CaseHub cases via tmux.*

---

## Overview

`claudony-casehub` is an optional Maven module within the Claudony project that implements the four CaseHub Worker Provisioner SPIs defined in `casehub-engine:api`. When on the classpath and enabled via config, it connects CaseHub's orchestration engine to Claudony's tmux session infrastructure and Qhorus channel mesh.

**Dependency direction:** `claudony-casehub` → `claudony-core` + `casehub-engine:api` + `casehub-ledger`. `claudony-app` → `claudony-core` + `claudony-casehub`. CaseHub never depends on Claudony. (ADRs: Claudony 0005, CaseHub 0001.)

---

## Module Structure

```
claudony/
├── pom.xml                    (parent, groupId=dev.claudony, packaging=pom)
├── claudony-core/             (extracted core: TmuxService, SessionRegistry, Session,
│   ├── pom.xml                ClaudonyConfig, expiry, fleet — no CaseHub)
│   └── src/
├── claudony-casehub/          (new: CaseHub SPI implementations — optional)
│   ├── pom.xml                (depends on claudony-core + casehub-engine:api + casehub-ledger)
│   └── src/
└── claudony-app/              (Quarkus application: REST, WebSocket, auth, dashboard,
    ├── pom.xml                agent MCP endpoint — depends on core + casehub)
    └── src/
```

`claudony-core` → pure library, no Quarkus app plugin.
`claudony-app` → Quarkus maven plugin, the runnable binary.
`claudony-casehub` → regular jar, discovered by Quarkus CDI at boot.

The restructuring from the current single-module layout is mechanical:
- `pom.xml` root becomes the parent (`<packaging>pom</packaging>`)
- Current `src/` + `pom.xml` → `claudony-app/` (after core extraction)
- Core services extracted to `claudony-core/`
- New `claudony-casehub/` created from scratch

---

## Configuration

All properties in `claudony-app/src/main/resources/application.properties`:

```properties
# CaseHub integration — disabled by default, opt-in
claudony.casehub.enabled=false

# CaseHub named datasource (casehub-ledger schema)
quarkus.datasource.casehub.db-kind=h2
quarkus.datasource.casehub.jdbc.url=jdbc:h2:file:~/.claudony/casehub;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=TRUE

# Capability-to-command mapping — default falls back to claude
# claudony.casehub.workers."code-reviewer".command=claude
# claudony.casehub.workers."researcher".command=ollama run llama3
claudony.casehub.workers.default.command=claude

# Working directory for provisioned workers
claudony.casehub.workers.default-working-dir=${claudony.default-working-dir}
```

**Activation:** The four `@ApplicationScoped` CDI beans are always present when the module is on the classpath. `claudony.casehub.enabled=false` causes `ClaudonyWorkerProvisioner.provision()` to throw `ProvisioningException("CaseHub integration disabled — set claudony.casehub.enabled=true")` as an early guard.

---

## SPI Implementations

### `ClaudonyWorkerProvisioner`

**Package:** `dev.claudony.casehub`
**Dependencies:** `TmuxService`, `SessionRegistry`, `ClaudonyConfig`, `ClaudonyWorkerContextProvider` (internal), `CaseLedgerEntryRepository` (via context provider)

`provision(Set<String> capabilities, ProvisionContext context)`:
1. Check `claudony.casehub.enabled` — throw `ProvisioningException` if false
2. Resolve command: look up `claudony.casehub.workers."<capability>".command` for each requested capability; fall back to `claudony.casehub.workers.default.command`
3. Generate `workerId = UUID.randomUUID().toString()`
4. Build `WorkerContext` from `ProvisionContext.caseId()` — queries `CaseLedgerEntryRepository` for prior workers, opens/finds the case channel, assembles lineage
5. Build startup prompt from `WorkerContext` (task description, channel name, prior worker summaries injected as system context)
6. Create tmux session: `tmuxService.createSession("claudony-worker-" + workerId, workingDir, command + buildPromptArgs(workerContext))`
7. Register in `SessionRegistry`
8. Return `new Worker(workerId, capabilitiesAsList, context -> Map.of())` — placeholder function for external worker

`terminate(String workerId)`:
- Kill tmux session via `TmuxService.killSession("claudony-worker-" + workerId)`
- Remove from `SessionRegistry`
- No-op if session doesn't exist (idempotent)

`getCapabilities()`:
- Returns all keys from `claudony.casehub.workers.*` config map (excluding "default")

---

### `ClaudonyCaseChannelProvider`

**Package:** `dev.claudony.casehub`
**Dependencies:** `QhorusMcpTools` (injected from `claudony-app`)

`openChannel(UUID caseId, String purpose)`:
- Channel name = `"case-" + caseId + "/" + purpose` (URL-safe slug)
- Calls `qhorusMcpTools.createChannel(channelName, purpose)`
- Returns `new CaseChannel(channelId, channelName, purpose, "qhorus", Map.of("qhorus-name", channelName))`

`postToChannel(CaseChannel channel, String from, String content)`:
- Extracts `channelName` from `channel.properties().get("qhorus-name")`
- Calls `qhorusMcpTools.sendMessage(channelName, from, "status", content, null, null)`

`closeChannel(CaseChannel channel)`:
- No-op — Qhorus channels are persistent; workers leaving the channel is sufficient

`listChannels(UUID caseId)`:
- Calls `qhorusMcpTools.listChannels()`
- Filters where channel name starts with `"case-" + caseId`
- Maps to `CaseChannel` records with `backendType = "qhorus"`

---

### `ClaudonyWorkerContextProvider`

**Package:** `dev.claudony.casehub`
**Dependencies:** `CaseLedgerEntryRepository`, `ClaudonyCaseChannelProvider`

`buildContext(String workerId, WorkRequest task)`:
1. **Clean-start check:** if `task.input().get("clean-start") == Boolean.TRUE` → return `WorkerContext(task.capability(), null, null, List.of(), PropagationContext.createRoot(), Map.of("clean-start", true))`
2. Extract `caseId` from `task.input().get("caseId")` — **convention:** CaseEngine populates `WorkRequest.input()` with at minimum `{"caseId": "<uuid>"}` when dispatching to a Claudony provisioner. If absent, return empty context (graceful degradation).
3. Query `caseLedgerEntryRepository` for entries of type `WORKER_EXECUTION_COMPLETED` for this case, ordered by `sequenceNumber`
4. Build `List<WorkerSummary>` — each entry becomes `WorkerSummary(actorId, actorRole, startedAt, completedAt, outputSummary-from-payload, ledgerEntry.id)`
5. Find channel: `caseChannelProvider.listChannels(caseId).stream().findFirst().orElse(null)`
6. Return `WorkerContext(task.capability(), caseId, channel, priorWorkers, PropagationContext.createRoot(), Map.of())`

---

### `ClaudonyWorkerStatusListener`

**Package:** `dev.claudony.casehub`
**Dependencies:** `SessionRegistry`

CaseEngine discovers this via CDI `Instance<WorkerStatusListener>` and calls it at lifecycle events.

`onWorkerStarted(String workerId, Map<String, String> sessionMeta)`:
- Find session in `SessionRegistry` by workerId
- Update status to `ACTIVE`
- Log session metadata for observability

`onWorkerCompleted(String workerId, WorkResult result)`:
- Update session status to `IDLE`
- If `result.status() == WorkStatus.FAULTED`: terminate session via `TmuxService.killSession()` and remove from registry
- Fire CDI event for dashboard notification

`onWorkerStalled(String workerId)`:
- Fire CDI event for dashboard stall notification
- Claudony's existing expiry infrastructure (`SessionIdleScheduler`) handles escalation if the session remains idle

---

## Test Strategy

**Unit tests** (pure JUnit 5, Mockito, in `claudony-casehub/src/test/`):

| Test | Coverage |
|---|---|
| `ClaudonyWorkerProvisionerTest` | Happy path: session created, workerId registered. Unknown capability → default command. Disabled → ProvisioningException. `terminate()` kills session and is idempotent. |
| `ClaudonyCaseChannelProviderTest` | openChannel returns CaseChannel with backendType="qhorus". postToChannel passes correct args. listChannels filters by caseId prefix. |
| `ClaudonyWorkerContextProviderTest` | `clean-start=true` returns empty priorWorkers. Normal path builds WorkerSummary list from ledger entries. Missing caseId in input → empty context. |
| `ClaudonyWorkerStatusListenerTest` | onWorkerStarted updates registry to ACTIVE. onWorkerCompleted sets IDLE. FAULTED result terminates session. onWorkerStalled fires CDI event. |

**Integration test** (`@QuarkusTest` in `claudony-app/src/test/`):
- Full stack with `quarkus-qhorus-testing` InMemory stores + in-memory SessionRegistry
- Provision a worker via REST → verify tmux session name created in TmuxService mock
- Lifecycle events flow through to SessionRegistry

---

## What Does Not Change

- `claudony-app`'s REST API, WebSocket, auth, dashboard — unchanged
- Qhorus integration (`MeshResource`, `quarkus-qhorus-testing`) — unchanged
- Session expiry policies — unchanged (they work on sessions regardless of how sessions were created)
- `PeerRegistry` and fleet — unchanged

---

## Out of Scope

- `claudony-casehub` reactive SPIs — `claudony-casehub` implements blocking SPIs only for Phase B
- `WorkerContextProvider` using `CaseLedgerEntry` cross-domain causal chain (WorkItem→Case) — future
- Dashboard UI changes to show CaseHub case graph — Phase B dashboard epic, separate spec
- CaseHub reactive datasource (`quarkus.datasource.casehub.reactive.*`) — Phase C
