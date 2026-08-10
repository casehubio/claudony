# Tmux Exit Watcher — Design Spec

**Issue:** casehubio/claudony#146  
**Branch:** `issue-146-tmux-exit-watcher`  
**Date:** 2026-06-05  
**Spec revision:** 3 (post-review-2)

---

## Problem

When a CaseHub worker (Claude CLI in a tmux session) terminates, the engine never finds out. `ClaudonyReactiveWorkerProvisioner.provision()` creates the tmux session and returns `ProvisionResult.empty()`. The engine then calls `WorkerExecutionManager.submit()` — currently satisfied by `NoOpWorkerExecutionManager @DefaultBean`, which does nothing. `WorkflowExecutionCompleted` is never published to the Vert.x event bus. The case instance stays stuck in WAITING indefinitely.

This is critical path item 1 toward real end-to-end case execution.

---

## Architectural Constraints

- **In-JVM event bus:** `WorkflowExecutionCompleted` is published to the Vert.x event bus (`EventBus.send()`), which is in-JVM only. The engine must be co-located in the same Quarkus JVM as Claudony. Fleet deployments where the engine runs separately require a different completion transport (out of scope).
- **Worker output is empty by design:** Claudony workers communicate via Qhorus channels, not via `WorkflowExecutionCompleted.output`. `Map.of()` in the completion event is intentional — the engine must not rely on output map data for case advancement when using Claudony workers.
- **tmux session option persistence:** tmux custom options (`@casehub_case_id`, `@casehub_role`) persist across Claudony JVM restarts but are lost on `tmux kill-server` or system reboot. Recovery after tmux restart degrades to "no watcher for orphaned sessions." A file-based supplement is out of scope for this iteration.

---

## Approach

Implement `ClaudonyWorkerExecutionManager` in `claudony-casehub` (consistent with all other CaseHub SPI implementations). Its `submit()` method is the engine's "start running" hook. The tmux session is already running at that point, so `submit()` starts a Java virtual thread watcher (`Thread.ofVirtual()`) that polls `TmuxService.sessionExists()`. When the session exits naturally, the watcher atomically claims the registry entry and publishes `WorkflowExecutionCompleted` via `EventBus.send()`.

Cancellation uses atomic registry removal as the gate — no separate cancellation map needed.

Recovery after server restart: persist caseId and roleName as tmux session custom options during provision, read them back during bootstrap, restart watchers by looking up `CaseInstance` via `CrossTenantCaseInstanceRepository`.

---

## Architecture

Engine lifecycle for a CaseHub worker:

1. Engine calls `ReactiveWorkerProvisioner.provision()` → Claudony calls `createWorkerSession()` (direct command, not shell+sendKeys), returns `ProvisionResult`
2. Engine calls `WorkerExecutionManager.submit(instance, worker, ...)` → "start running"
3. Worker (Claude CLI) runs in tmux session; session closes when Claude exits (no shell wrapper)
4. Watcher detects session gone → atomically claims registry entry → publishes `WorkflowExecutionCompleted` via `EventBus.send()` → case advances

Two execution paths:
- **Normal**: engine calls `submit()` with full `CaseInstance` + `Worker` context → watcher started
- **Recovery**: server restart, bootstrap reads caseId from tmux options → `bootstrapCasehubWatchers()` calls `watch()` directly with `CaseInstance` fetched from `CrossTenantCaseInstanceRepository`

**Accepted risk — provision-to-submit gap:** `CaseInstance` and `Worker` objects only exist inside the engine and are only passed to Claudony at `submit()` time. The gap between `provision()` returning and `submit()` being called is real: if Claude exits during this window, no watcher ever starts and the case is stuck. `provision()` has no access to these objects (it receives `Set<String> capabilities` and `ProvisionContext` only). The window is small in normal engine operation; the accepted risk is noted here explicitly.

---

## Components

### `TmuxService` additions

```java
// Direct command execution — session closes when command exits (no shell wrapper)
void createWorkerSession(String name, String workingDir, String command)
// → tmux new-session -d -s <name> -c <workingDir> -- <command>
// → tmux set-option -t <name> remain-on-exit off   ← explicit, overrides ~/.tmux.conf

void setSessionOption(String name, String key, String value)
// → tmux set-option -t <name> <key> <value>

Optional<String> getSessionOption(String name, String key)
// → tmux show-options -t <name> -v <key>, returns Optional.empty() if absent or error
```

`createSession()` (existing, shell-based) is unchanged — used for regular user sessions. CaseHub workers use `createWorkerSession()` exclusively. The distinction matters: with a shell wrapper, `tmux has-session` returns 0 even after Claude exits (the shell is still running). With direct command execution, when Claude exits the pane dies and — with `remain-on-exit off` explicitly enforced — the session closes regardless of `~/.tmux.conf`. `sessionExists()` then correctly returns false.

`remain-on-exit off` is set explicitly after `new-session` via a second `set-option` call. Relying on the tmux default is insufficient: a developer with `remain-on-exit on` in their config would see the session persist indefinitely with `[exited]` displayed, and the watcher would never fire.

### `SessionRegistry.remove()` return type change

Change signature from `void remove(String id)` to `Session remove(String id)`. Returns the removed session, or null if absent. This enables the atomic publish gate in the watcher.

### `ClaudonyReactiveWorkerProvisioner` changes

1. `doProvision()` — call `createWorkerSession()` instead of `createSession()`. After creation:
   - `tmux.setSessionOption(sessionName, "@casehub_case_id", caseId.toString())`
   - `tmux.setSessionOption(sessionName, "@casehub_role", roleName)`

2. `terminate(workerId)` — reorder: `registry.remove()` FIRST, then `tmux.killSession()`. This ensures the watcher's atomic gate (`registry.remove()`) catches any concurrent natural-exit publish attempt.

### `ClaudonyWorkerExecutionManager` (new, `claudony-casehub`)

Implements `WorkerExecutionManager`, `@ApplicationScoped` — overrides `NoOpWorkerExecutionManager @DefaultBean`.

Injected: `TmuxService`, `SessionRegistry`, `WorkerSessionMapping`, `CaseHubConfig`, Vert.x `EventBus`.

Active watchers tracked in `ConcurrentHashMap<String, Thread> watchers` (keyed by sessionId) — used for lifecycle management (shutdown) and duplicate-submit guard.

**All three SPI methods:**

```java
// submit() — resolves session, delegates to watch()
Uni<Void> submit(Long eventLogId, CaseInstance instance, Worker worker,
                 Capability capability, Map<String, Object> inputData) {
    if (!config.enabled()) return Uni.createFrom().voidItem();
    UUID caseId = instance.getUuid();
    String roleName = worker.getName();
    Optional<String> sessionId = sessionMapping.findByCase(caseId.toString(), roleName);
    if (sessionId.isEmpty()) {
        LOG.warnf("No session found for case %s / role %s — watcher not started", caseId, roleName);
        return Uni.createFrom().voidItem();
    }
    watch(sessionId.get(), SESSION_PREFIX + sessionId.get(), instance, worker);
    return Uni.createFrom().voidItem();
}

// schedulePersistedEvent() — no-op: tmux workers have no Quartz persistent events
Uni<Void> schedulePersistedEvent(EventLog scheduledEventLog) {
    return Uni.createFrom().voidItem();
}

// getActiveWorkCount() — capacity limiting; return count of active watchers for this workerId.
// The engine passes the worker definition name as workerId; Claudony stores this as roleName
// in sessionToRole — they are the same string.
int getActiveWorkCount(String workerId) {
    return (int) sessionToRole.values().stream()
        .filter(workerId::equals)
        .count();
}
```

For `getActiveWorkCount`, a reverse map `ConcurrentHashMap<String, String> sessionToRole` (sessionId → roleName) populated in `watch()` supports the lookup without iterating the full `SessionRegistry`.

**`watch()` — package-private, core watcher start:**

```java
void watch(String sessionId, String sessionName, CaseInstance instance, Worker worker) {
    Thread watcher = Thread.ofVirtual()
        .name("casehub-watcher-" + sessionId)
        .unstarted(watcherRunnable(sessionId, sessionName, instance, worker));
    // Populate sessionToRole BEFORE putIfAbsent so getActiveWorkCount() never transiently
    // undercounts — if putIfAbsent loses the race, clean up.
    sessionToRole.put(sessionId, worker.getName());
    if (watchers.putIfAbsent(sessionId, watcher) != null) {
        sessionToRole.remove(sessionId);
        LOG.warnf("Duplicate watch request for session %s — ignoring", sessionId);
        return;
    }
    watcher.start();
}
```

**Watcher runnable — virtual thread loop:**

```java
// Full structure including exception handling. finally ensures map cleanup even on
// unexpected RuntimeException escaping the loop.
try {
    do {
        if (Thread.currentThread().isInterrupted()) break;  // @PreDestroy shutdown
        if (!registry.find(sessionId).isPresent()) break;   // terminated — no publish
        try {
            boolean exists = tmuxService.sessionExists(sessionName);
            consecutiveFailures = 0;
            if (!exists) {
                // Atomic gate: whichever caller wins registry.remove() publishes
                if (registry.remove(sessionId) != null) {
                    String idempotencyKey =
                        instance.getUuid() + ":" + worker.getName() + ":" + sessionId;
                    eventBus.send(EventBusAddresses.WORKER_EXECUTION_FINISHED,
                        new WorkflowExecutionCompleted(instance, worker, idempotencyKey, Map.of()));
                }
                break;
            }
        } catch (IOException e) {
            if (++consecutiveFailures >= maxPollFailures) {
                LOG.errorf("Session %s: %d consecutive poll failures — abandoning watcher",
                    sessionId, maxPollFailures);
                break;  // no publish
            }
            LOG.warnf("Session %s: poll failure (%d/%d): %s",
                sessionId, consecutiveFailures, maxPollFailures, e.getMessage());
        }
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();  // restore interrupted flag
            break;
        }
    } while (true);
} finally {
    watchers.remove(sessionId);
    sessionToRole.remove(sessionId);
}
```

First check runs immediately (do-while) — reduces detection latency for fast-exiting workers.

**Shutdown hook:**

```java
@PreDestroy
void shutdown() {
    watchers.values().forEach(Thread::interrupt);
    watchers.clear();
    sessionToRole.clear();
}
```

### `ServerStartup` changes

`bootstrapRegistry()` — after registering each session, query:
```java
tmux.getSessionOption(name, "@casehub_case_id") → caseId
tmux.getSessionOption(name, "@casehub_role")    → roleName
```
If present, populate `caseId` and `roleName` on the `Session` record.

New `bootstrapCasehubWatchers()` — annotated `@Blocking`, called from `onStart()` when `isServerMode() && casehubConfig.enabled()`:

```java
@Blocking
void bootstrapCasehubWatchers() {
    for (Session session : registry.all()) {
        if (session.caseId().isEmpty()) continue;
        UUID caseId = UUID.fromString(session.caseId().get());
        try {
            CaseInstance instance = caseInstanceRepo.findByUuid(caseId)
                .await().atMost(Duration.ofSeconds(5));
            if (instance == null) {
                LOG.infof("No CaseInstance for caseId %s — skipping recovery watcher", caseId);
                continue;
            }
            String roleName = session.roleName().orElse("worker");
            Worker worker = new Worker(roleName, List.of(), ctx -> Map.of());
            // Call watch() directly — WorkerSessionMapping is empty after restart
            workerExecManager.watch(session.id(), session.name(), instance, worker);
        } catch (TimeoutException e) {
            LOG.warnf("Timed out looking up caseId %s during recovery — skipping", caseId);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to look up caseId %s during recovery — skipping", caseId);
        }
    }
}
```

`Uni.await().atMost()` throws `TimeoutException` on deadline expiry — it does not return null. The null check handles genuine "case not found" (repository returns null Uni item). Both are caught per-session so a single failure does not abort recovery for remaining sessions.

**`worker` stub and `WorkflowExecutionCompletedHandler` interaction:**

`worker` is `new Worker(roleName, List.of(), ctx -> Map.of())`. The engine handler is `WorkflowExecutionCompletedHandler` (`@ConsumeEvent(WORKER_EXECUTION_FINISHED)`). What it does with `Worker`:

- `worker.getName()` — used in EventLog (`setWorkerId`), `caseResumptionService.resumeIfWaiting()`, `workerStatusListener.onWorkerCompleted()`, `WorkerDecisionEvent`. Safe — stub has roleName.
- `worker.getCapabilities()` — used in `findMatchingCapabilityBinding()` to find the binding with a matching `CapabilityTarget`. With empty capabilities, the match fails and `capabilityTag` is null in `WorkerDecisionEvent`. **Case advancement is unaffected** — binding lookup is used only for conflict resolution (skipped because `rawOutput` is `Map.of()`) and capability tagging for trust scoring.
- **Accepted limitation in recovery path:** null `capabilityTag` means the `WorkerDecisionEvent` for this completion carries no capability tag, which may silently omit a trust score update for the worker's capability. Case advancement is correct; trust scoring is degraded for recovered completions.

The `idempotencyKey` is deterministic (`caseId:roleName:sessionId`), so if the watcher fires twice, the engine's idempotency check suppresses the duplicate.

### New compile dependencies

`casehub-engine-common` (compile scope) added to `claudony-casehub/pom.xml`:
- Provides: `WorkerExecutionManager`, `WorkflowExecutionCompleted`, `EventBusAddresses`, `CaseInstance`, `EventLog`, `CrossTenantCaseInstanceRepository`

`quarkus-vertx-mutiny` (or the BOM-managed Vert.x dep) added to `claudony-casehub/pom.xml`:
- Provides: Vert.x `EventBus`

`casehub-engine-common` removed from `claudony-app/pom.xml` test scope (no longer needed there — `NoOpWorkerExecutionManager` moves to `claudony-casehub` test sources or is deleted if `ClaudonyWorkerExecutionManager` handles all profiles).

### Configuration

New properties in `CaseHubConfig`:
- `claudony.casehub.worker-exit-poll-ms` (default `5000`)
- `claudony.casehub.worker-exit-max-poll-failures` (default `3`)

---

## Data Flow

### Normal path

```
provision()
  → tmux.createWorkerSession(sessionName, workingDir, command)   ← direct, no shell
  → tmux.setSessionOption(sessionName, "@casehub_case_id", caseId)
  → tmux.setSessionOption(sessionName, "@casehub_role", roleName)
  → registry.register(session with caseId + roleName)
  → sessionMapping.register(roleName, caseId, sessionId)
  → return ProvisionResult.empty()

  [gap: engine processes events, calls submit()]

submit(eventLogId, instance, worker, capability, inputData)
  → if !enabled: return
  → sessionId = sessionMapping.findByCase(caseId, roleName)
  → watch(sessionId, SESSION_PREFIX + sessionId, instance, worker)

watcherLoop (virtual thread, do-while, immediate first check):
  1. registry.find(sessionId)? → absent → stop (terminated)
  2. sessionExists(sessionName)? → false → registry.remove(sessionId) != null?
       → yes: eventBus.send(WORKER_EXECUTION_FINISHED, WorkflowExecutionCompleted(...))
       → no:  stop (terminate() won the race)
  3. sleep(pollIntervalMs), repeat

terminate(workerId):
  1. registry.remove(workerId)   ← FIRST (watcher's atomic gate)
  2. tmux.killSession(...)
```

### Recovery path

```
onStart()
  → bootstrapRegistry()
      → for each claudony-worker-* session in tmux:
          → getSessionOption(@casehub_case_id) → caseId (if present)
          → getSessionOption(@casehub_role)    → roleName
          → register Session(caseId, roleName populated)
  → bootstrapCasehubWatchers() [@Blocking, if server + casehub enabled]
      → for each session with caseId:
          → caseInstanceRepo.findByUuid(caseId).await().atMost(5s)
          → if null: log INFO, skip
          → worker = new Worker(roleName, List.of(), ctx -> Map.of())
          → workerExecManager.watch(session.id(), session.name(), instance, worker)
          ↑ calls watch() directly — WorkerSessionMapping is empty after restart
```

---

## Error Handling

| Scenario | Behaviour |
|----------|-----------|
| `submit()` with no session in mapping | Log WARN, return — watcher not started; engine SLA handles timeout |
| `sessionExists()` throws IOException | Log WARN, increment failure counter; after `maxPollFailures` log ERROR + exit watcher without publishing |
| Concurrent `registry.remove()` from watcher and `terminate()` | Atomic gate: `registry.remove()` returns non-null only once; only one caller publishes |
| `EventBus.send()` fails | Log ERROR; no retry — in-JVM delivery is reliable under normal conditions |
| Recovery: `findByUuid()` returns null | Log INFO, skip — engine restarted or case already resolved |
| Recovery: session exits between bootstrap and watcher start | First poll returns false; watcher atomically claims registry and publishes — correct |
| Recovery: `WorkerSessionMapping` empty after restart | `bootstrapCasehubWatchers()` calls `watch()` directly; `session.id()` is sessionId, `session.name()` is sessionName |
| Duplicate `submit()` for same session | `putIfAbsent` check: log WARN, return without starting second watcher |
| Quarkus shutdown with active watchers | `@PreDestroy` interrupts all watcher threads; sleeping threads wake and exit cleanly |
| tmux restarted (not just Claudony) | tmux options lost; recovered sessions treated as non-casehub; cases left stuck |

---

## Testing

### Unit: `ClaudonyWorkerExecutionManagerTest`

No Quarkus context. Direct instantiation with mocks.

| Test | Assertion |
|------|-----------|
| `submit_startsWatcher_publishesOnSessionExit` | sessionExists() returns false on first call; eventBus.send() called once at WORKER_EXECUTION_FINISHED |
| `submit_doesNotPublish_whenTerminateWinsAtomicGate` | registry.remove() by terminate() before watcher claims it; no send() |
| `submit_doesNothing_whenCasehubDisabled` | config.enabled() false; no thread created |
| `submit_doesNothing_whenNoSessionFound` | sessionMapping.findByCase() returns empty; no thread created |
| `watcher_stopsAfterMaxConsecutiveIoFailures` | sessionExists() throws 3 times; no send(), watcher exits |
| `watch_ignoresDuplicateSubmit` | second watch() call for same sessionId logs WARN and returns without starting thread |
| `race_watcherDetectsExit_terminateWinsAtomicGate_noPublish` | Force ordering via CountDownLatch: watcher thread calls sessionExists() → false, then blocks before registry.remove(); terminate() runs and removes registry entry; watcher resumes, loses atomic gate, no send() |
| `shutdown_interruptsActiveWatchers` | @PreDestroy called with sleeping watcher; verify watcher exits cleanly |
| `getActiveWorkCount_returnsCorrectCount` | Two sessions for same role; getActiveWorkCount returns 2 |

### Integration: `ClaudonyWorkerExecutionManagerIntegrationTest`

`@QuarkusTest`, `CasehubEnabledProfile`. `@InjectMock TmuxService` stubbed with `sessionExists()` returning `false` immediately. Verifies `submit()` causes `WorkflowExecutionCompleted` on event bus within Awaitility window.

### `CaseEngineRoundTripTest` update

Remove manual `eventBus.publish(WORKER_EXECUTION_FINISHED, ...)`. Add stub: `when(tmuxService.sessionExists(anyString())).thenReturn(false)`. Watcher fires `WorkflowExecutionCompleted` automatically. Awaitility assertion on lineage unchanged.

### `TmuxServiceTest` additions

- `createWorkerSession_sessionClosesWhenCommandExits` — verify tmux session disappears after command exits (real tmux)
- `setSessionOption_writesOption` — real tmux session, option readable after set
- `getSessionOption_readsOption` — reads back what was set
- `getSessionOption_returnsEmpty_whenKeyAbsent`

### Recovery integration: `WorkerExitRecoveryIntegrationTest`

`@QuarkusTest`, `CasehubEnabledProfile`. Tests the watcher-start and publish path of the recovery sequence:
1. Seed `SessionRegistry` directly with a session record that has `caseId` and `roleName` populated — simulating what `bootstrapRegistry()` produces after reading tmux options. (`createWorkerSession()` is a provision-time call and is not involved in recovery.)
2. Stub `CrossTenantCaseInstanceRepository.findByUuid()` to return a `CaseInstance`
3. Invoke `bootstrapCasehubWatchers()` directly (package-private)
4. Stub `sessionExists()` to return `false`
5. Awaitility assert `eventBus.send()` fires at `WORKER_EXECUTION_FINISHED` — the watcher runs on a virtual thread asynchronously so the assertion requires a wait

(Full stop-restart simulation is impractical in a `@QuarkusTest`. The bootstrap-from-tmux-options path is covered by `bootstrapRegistry()` unit tests in `ServerStartupTest`.)

### `ServerStartupTest` additions

- `bootstrapRegistry_populatesCaseIdAndRole_fromTmuxOptions` — mock `getSessionOption()` returning values; assert session registered with caseId/roleName
- `bootstrapCasehubWatchers_startsWatcherForCasehubSessions` — mock CaseInstanceRepository and workerExecManager; assert `watch()` called per casehub session

---

## Invariants

- `registry.remove()` returning non-null is the only gate to publishing `WorkflowExecutionCompleted` — prevents double-publish in all concurrent orderings
- `terminate()` always removes from registry before killing tmux
- `createWorkerSession()` uses direct command execution — session lifespan = command lifespan
- `createSession()` (regular sessions) is unchanged
- `EventBus.send()` not `publish()` — point-to-point to the engine's single consumer
- Idempotency key is deterministic: `caseId + ":" + roleName + ":" + sessionId`
- Watcher threads are Java virtual threads (`Thread.ofVirtual()`) — not Vert.x worker pool threads
- `bootstrapCasehubWatchers()` is `@Blocking` — no blocking await on Vert.x I/O thread
- Worker output is always `Map.of()` — Claudony workers communicate via Qhorus channels
