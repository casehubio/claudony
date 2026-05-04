# Case Worker Panel SSE Push — Design

**Issue:** casehubio/claudony#104  
**Epic:** casehubio/claudony#99 (channel gateway integration maturity)  
**Date:** 2026-05-05

---

## Problem

The case worker panel polls `GET /api/sessions?caseId=X` every 3 seconds via `setInterval`. This adds unnecessary HTTP traffic proportional to open panel tabs, introduces up to 3 seconds of latency on status changes, and does not scale well in fleet deployments. `ClaudonyWorkerStatusListener` already receives CDI events on every worker lifecycle transition — wiring those events to a server-sent event stream gives the panel real-time updates with no polling overhead.

---

## Architecture

```
ClaudonyWorkerStatusListener
  │  onWorkerStarted / onWorkerCompleted / onWorkerStalled
  ▼
CaseEventBroadcaster (@ApplicationScoped)
  │  snapshot = registry.findByCaseId(caseId) serialised as JSON
  │  delegates to CaseWorkerUpdateStrategy
  ▼
CaseWorkerUpdateStrategy (SPI)
  ├── EventsOnlyStrategy    — lifecycle events only, no periodic emission
  ├── HybridStrategy        — events + configurable heartbeat (default: 30 s)  ← default
  └── RegistryHooksStrategy — fires on any SessionRegistry mutation for case sessions
  ▼
SessionResource  GET /{id}/case-events  (text/event-stream)
  │  resolves session → caseId → 404 if absent or standalone
  │  emits current snapshot immediately on connect (handles reconnects)
  │  returns broadcaster.subscribe(caseId) Multi<String>
  ▼
terminal.js  EventSource('/api/sessions/{sessionId}/case-events')
  replaces setInterval(pollWorkers, 3000)
  browser auto-reconnects → endpoint re-emits fresh state on each new connection
```

---

## Self-Contained Verification

No engine or Qhorus dependency. All data already lives in `SessionRegistry`. The SSE infrastructure pattern is proven by `MeshResource.events()`. The terminal WebSocket is not reused — mixing binary terminal data with JSON control frames was explicitly rejected.

---

## SPI: CaseWorkerUpdateStrategy

```java
public interface CaseWorkerUpdateStrategy {
    /** Called by CaseEventBroadcaster on every lifecycle event. */
    void onLifecycleEvent(String caseId);

    /**
     * Returns a Multi that emits SSE payloads for the given case.
     * The first item must be the current snapshot (ensures reconnect gives fresh state).
     * snapshotFn produces the current JSON worker list on demand.
     */
    Multi<String> subscribe(String caseId, Supplier<String> snapshotFn);

    /** Factory — reads claudony.case-worker-update config. */
    static CaseWorkerUpdateStrategy named(String name, long heartbeatMs) { ... }
}
```

**EventsOnlyStrategy:** Holds `ConcurrentHashMap<caseId, List<MultiEmitter<String>>>`. `subscribe()` emits initial snapshot synchronously then registers an emitter. `onLifecycleEvent()` emits to all registered emitters for that caseId. No periodic tick.

**HybridStrategy (default):** Same as EventsOnly plus a shared `Multi.createFrom().ticks().every(heartbeatMs)` that re-emits the current snapshot for all registered cases. Serves dual purpose: keep-alive (prevents proxy connection timeouts) and drift correction (catches state changes that bypass lifecycle events — tmux crash, idle expiry, manual session delete).

**RegistryHooksStrategy:** `SessionRegistry` gains `addChangeListener(Consumer<String> caseIdListener)` — invoked in `updateStatus()` and `remove()` when the affected session has a caseId. Strategy subscribes at startup and treats every registry mutation as a lifecycle event. Provides instant accuracy regardless of how state changed.

---

## Config Properties

```properties
# Strategy selection (events-only | hybrid | registry-hooks)
claudony.case-worker-update=hybrid

# Heartbeat interval for hybrid strategy
claudony.case-worker-heartbeat-ms=30000
```

---

## SSE Endpoint

```java
@GET
@Path("/{id}/case-events")
@Produces("text/event-stream")
public Response caseEvents(@PathParam("id") String id) {
    return registry.find(id)
        .filter(s -> s.caseId().isPresent())
        .map(s -> Response.ok(
            broadcaster.subscribe(s.caseId().get())).build())
        .orElse(Response.status(404).build());
}
```

- **404** for unknown session or session without caseId
- **Initial snapshot** emitted immediately on every new connection — reconnects always show fresh state
- **SSE payload:** `data: [{SessionResponse...},...]\n\n` — same JSON shape as `GET /api/sessions?caseId=X`
- **Cleanup:** `Multi.on().termination()` removes the emitter on client disconnect — no memory leak

---

## Frontend Changes (terminal.js)

Remove:
- `var casePoller`
- `setInterval(pollWorkers, 3000)` (two occurrences)
- `clearInterval(casePoller)` (two occurrences)
- `pollWorkers()` function

Add:
- `var caseEventSource = null`
- `connectCaseEvents()` — creates `EventSource('/api/sessions/{sessionId}/case-events')`, wires `onmessage → renderWorkers(JSON.parse(e.data))`
- `openCasePanel()` calls `connectCaseEvents()` when no connection exists
- `closeCasePanel()` calls `caseEventSource.close(); caseEventSource = null`
- `switchToWorker()` closes old EventSource before opening new one
- `beforeunload` closes EventSource

`renderWorkers()` is unchanged — receives the same JSON array it received from polling.

---

## Files Changed

| File | Change |
|---|---|
| `claudony-app/.../server/CaseWorkerUpdateStrategy.java` | New — SPI interface + factory |
| `claudony-app/.../server/CaseEventBroadcaster.java` | New — @ApplicationScoped broadcaster |
| `claudony-app/.../server/strategy/EventsOnlyStrategy.java` | New |
| `claudony-app/.../server/strategy/HybridStrategy.java` | New |
| `claudony-app/.../server/strategy/RegistryHooksStrategy.java` | New |
| `claudony-app/.../server/SessionResource.java` | Add `GET /{id}/case-events` endpoint |
| `claudony-casehub/.../ClaudonyWorkerStatusListener.java` | Inject `CaseEventBroadcaster`, call `emit(caseId)` on lifecycle events |
| `claudony-core/.../server/SessionRegistry.java` | Add `addChangeListener()` for RegistryHooksStrategy |
| `claudony-app/.../resources/app/terminal.js` | Replace polling with EventSource |
| `claudony-app/.../resources/app/session.html` | No structural change |
| `claudony-app/src/main/resources/application.properties` | New config defaults |
| `dev/claudony/config/ClaudonyConfig.java` | New config properties |

---

## Test Strategy

### Unit tests — CaseEventBroadcaster + strategies

| Test | Verifies |
|---|---|
| `subscribe_emitsInitialSnapshotOnConnect` | First Multi item is current worker list |
| `onLifecycleEvent_pushesToAllSubscribersForCase` | All open connections for caseId receive event |
| `onLifecycleEvent_doesNotPushToOtherCase` | Events are case-scoped, not global |
| `subscribe_emitsHeartbeat_afterInterval` | Hybrid: tick fires after configured interval |
| `eventsOnly_noHeartbeat_withoutLifecycleEvent` | No emission without trigger |
| `clientDisconnect_removesEmitter_noLeak` | Termination handler cleans up emitter map |
| `multipleClients_sameCase_allReceiveEvent` | 3 concurrent subscribers all receive push |
| `unknownCase_subscribe_returnsEmptyStream` | No crash on caseId with no sessions |
| `registryHooks_fires_onStatusUpdate` | `updateStatus()` triggers emission |
| `registryHooks_fires_onRemove` | `remove()` triggers emission for case sessions |
| `registryHooks_ignoresStandaloneSessions` | No emission for sessions without caseId |

### Integration tests — @QuarkusTest (SessionResourceCaseEventsTest)

| Test | Verifies |
|---|---|
| `caseEvents_404_forUnknownSession` | Endpoint returns 404 |
| `caseEvents_404_forStandaloneSession` | No caseId → 404 |
| `caseEvents_emitsInitialState_onConnect` | First SSE event arrives immediately |
| `caseEvents_contentType_isEventStream` | `Content-Type: text/event-stream` |
| `caseEvents_emitsUpdate_whenBroadcasterFires` | Direct `broadcaster.emit(caseId)` → client receives update |
| `caseEvents_multipleClients_bothReceiveUpdate` | Two simultaneous connections both get pushed |
| `strategyConfig_hybrid_isDefault` | `claudony.case-worker-update` defaults to hybrid |
| `strategyConfig_eventsOnly_respected` | `%test.claudony.case-worker-update=events-only` → no heartbeat |

### E2E tests — Playwright (CaseWorkerPanelE2ETest additions)

| Test | Verifies |
|---|---|
| `casePanel_showsWorkers_immediately_onOpen` | Initial state visible without waiting for poll interval |
| `casePanel_updatesWorkers_onSSEPush` | Status change → panel updates in real time |
| `casePanel_noPollingInterval_inSource` | No `setInterval` for worker updates in JS (grep assertion) |
| `casePanel_closingPanel_closesEventSource` | No open connections after panel close |
| `casePanel_switchWorker_reconnectsEventSource` | Switching worker opens new SSE connection |

### Correctness invariants (verified by tests or grep)

- No `setInterval` for `pollWorkers` remains in `terminal.js`
- `beforeunload` closes EventSource
- `switchToWorker()` closes old EventSource before opening new one
- Heartbeat interval is configurable and tested with short interval (`%test.claudony.case-worker-heartbeat-ms=500`)
- Strategy selected by config property verified in `@QuarkusTest`

---

## Documentation Updates

- **CLAUDE.md:** test count, component list (CaseEventBroadcaster, CaseWorkerUpdateStrategy, strategy package), config properties, remove 3-second polling references
- **DESIGN.md:** component list, architecture notes (polling → SSE push), config table
- **docs/BUGS-AND-ODDITIES.md:** check for any polling limitation entries — remove/update
- **docs/superpowers/plans/2026-04-28-case-worker-panel.md:** update polling references
- **casehubio/parent#11:** referenced (per-case runtime selection tracked there)
- **Commits:** `Refs #104, Refs #99` on all; `Closes #104` on final

---

## Commit Plan

> **Note:** Implementation follows TDD — tests are written before or alongside each implementation commit, not after. The writing-plans skill will expand this into the correct red-green-refactor order.

1. `feat(config): add case-worker-update and case-worker-heartbeat-ms config properties` — Refs #104
2. `feat(server): add CaseWorkerUpdateStrategy SPI with three implementations` — Refs #104
3. `feat(server): add CaseEventBroadcaster — event-driven SSE broadcaster for case worker panel` — Refs #104
4. `feat(core): add SessionRegistry.addChangeListener for registry-hooks strategy` — Refs #104
5. `feat(casehub): wire ClaudonyWorkerStatusListener to CaseEventBroadcaster` — Refs #104
6. `feat(rest): add GET /api/sessions/{id}/case-events SSE endpoint` — Refs #104
7. `feat(frontend): replace case worker poll with EventSource SSE subscription` — Refs #104
8. `test: CaseEventBroadcaster unit tests + SessionResourceCaseEventsTest integration` — Refs #104
9. `test(e2e): CaseWorkerPanelE2ETest SSE behaviour` — Refs #104
10. `docs: update DESIGN.md, CLAUDE.md, plans for #104 SSE push` — Closes #104, Refs #99
