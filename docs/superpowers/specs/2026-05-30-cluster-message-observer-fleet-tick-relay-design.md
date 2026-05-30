# Design: CLUSTER MessageObserver — Fleet Tick Relay
**Issue:** casehubio/claudony#118  
**Date:** 2026-05-30  
**Branch:** issue-118-cluster-message-observer

---

## Problem

Claudony embeds Qhorus per node. In a fleet deployment with shared PostgreSQL Qhorus, when a worker posts a message on Node B, the CDI fan-out fires on Node B only — browsers connected to Node A receive nothing. `ClaudonyChannelBackend` is LOCAL-scoped and does not cross node boundaries.

---

## Deployment Model

Production fleet deployments use shared PostgreSQL for Qhorus. H2 is for single-node installs and testing only. The cross-node delivery problem only arises in multi-node fleet; H2 multi-node is not a supported topology.

Because all fleet nodes share one Qhorus data source, a browser on any node can fetch the full message history from its local Qhorus handle. The only missing piece is the real-time tick notification crossing node boundaries.

---

## Approach: Tick Relay via HTTP

A CLUSTER-scoped `MessageObserver` implementation in Claudony relays a channel-name tick to all healthy fleet peers on every message. Each peer calls `ChannelEventBus.emit(channelName)`, which drives the existing SSE delivery to connected browsers. The browser fetches message content from its local Qhorus handle, which resolves to the shared PG — the message is present.

**Why tick-only (no payload relay):** `ChannelNotifyRequest` carries only `channelName`. Message content is not relayed. This is intentionally aligned with the shared-PG trajectory (`2026-05-28-reactive-qhorus-pg.md`) — the peer's Qhorus handle is the authoritative source. If the reactive Qhorus PG plan lands first, no redesign of this component is needed.

---

## Architecture

### New components (all in `claudony-app/server/fleet/`)

| Class | CDI scope | Purpose |
|---|---|---|
| `FleetMessageRelayObserver` | `@ApplicationScoped` | CLUSTER-scoped `MessageObserver`; relays tick to every healthy peer |
| `ChannelNotifyRequest` | record | `{String channelName}` — fleet relay payload |

`@ApplicationScoped` is required: `PeerRegistry` injection resolves correctly, the test can `@Inject FleetMessageRelayObserver observer` directly, and `MessageObserverDispatcher` discovers it via `Instance<MessageObserver>`.

### Modified components

| Class | Change |
|---|---|
| `ChannelSyncResource` | Add `POST /api/internal/channels/notify` → calls `ChannelEventBus.emit(channelName)` |
| `PeerClient` | Add `notifyChannel(ChannelNotifyRequest)` method |

No changes to `ChannelEventBus`, `MeshResource`, or frontend JavaScript.

---

## Data Flow

1. Worker on Node B calls `MessageService.dispatch()` (blocking) or `ReactiveMessageService.dispatch()` (reactive) → message persisted to shared PG
2. `MessageObserverDispatcher` calls `FleetMessageRelayObserver.onMessage(event)` on Node B — fires before `fanOut` in both services (observer is at line 265, `fanOut` at line 277 in blocking; both post-commit in reactive)
3. Observer guards: if `event.channelName() == null`, return immediately. Otherwise reads `event.channelName()`, calls `peerRegistry.getHealthyPeers()`. For each peer: `Thread.ofVirtual().start(() -> relayToPeer(peer, channelName))`
4. Returns immediately
5. `ChannelGateway.fanOut()` calls `ClaudonyChannelBackend.post()` on Node B → `ChannelEventBus.emit(channelName)` → Node B SSE subscribers tick (existing behaviour)
6. Each virtual thread: build `PeerClient` via `RestClientBuilder` + `FleetKeyClientFilter`, POST `ChannelNotifyRequest{channelName}` to Node A at `POST /api/internal/channels/notify`
7. Node A: `@RolesAllowed("fleet")` passes, `ChannelEventBus.emit(channelName)` fires
8. Node A SSE subscribers tick → browser fetches from Node A's local Qhorus handle → shared PG → message present → renders

**No relay loop:** the fleet endpoint calls `ChannelEventBus.emit()` directly and never enters the Qhorus dispatch pipeline. Node A's `FleetMessageRelayObserver` does not fire.

**LAST_WRITE exclusion:** `MessageService.dispatch()` returns before calling `MessageObserverDispatcher` for LAST_WRITE overwrites (same sender overwrites existing message). `FleetMessageRelayObserver.onMessage()` is never called for overwrites. Claudony channels are APPEND-semantic (`NormativeChannelLayout`, `SimpleLayout`), so this has no practical impact — but it is a non-obvious scope boundary: LAST_WRITE overwrite ticks do not cross node boundaries.

---

## Error Handling

**Pattern:** matches `ChannelFleetBroadcaster` for individual failure categories, but the observer fires on every message (not just channel creation). In a busy deployment, auth failures trip the circuit breaker within seconds — fail-fast is the correct outcome. WARN log level for all failures, consistent with `ChannelFleetBroadcaster`.

| Scenario | Behaviour |
|---|---|
| Peer returns 2xx | `peerRegistry.recordSuccess(peer.id())` |
| Peer returns non-2xx | `LOG.warnf(...)`, `peerRegistry.recordFailure(peer.id())` |
| Peer unreachable / exception | Same — exception caught in `relayToPeer()`, `recordFailure()` called |
| No healthy peers | `getHealthyPeers()` returns empty → return immediately, zero overhead |
| Fleet key absent / 401 | WARN + `recordFailure()`. Higher message frequency means circuit trips faster — desired fail-fast |
| `event.channelName() == null` | Guard at top of `onMessage()` — return immediately, no peers contacted |

**Exception containment:** virtual thread runs a private `relayToPeer(peer, channelName)` method with a `try/catch` wrapping the full body. Exceptions do not propagate to the virtual thread's uncaught handler and cannot reach the `MessageObserverDispatcher` loop. Without the private method pattern, `recordFailure()` would not be called on exception.

**Pre-commit race — blocking path only:** in the blocking `MessageService.dispatch()`, `onMessage()` fires inside the open transaction. Virtual threads spawned before a rollback will POST ticks to peers for a message that was never committed. The peer's browser ticks, fetches from shared PG, finds nothing — benign (spurious tick, no phantom data). Consistent with how `InProcessMessageBus.fireAsync()` handles the same race.

**Reactive path:** `ReactiveMessageService.dispatch()` fires observers post-commit (Phase 4, after `Panache.withTransaction()` completes — correctness fix, refs qhorus#193). No pre-commit race on the reactive path. qhorus#166 (after-commit dispatch for the blocking service) remains open.

**Channel scope:** relay all channels — no `case-*` prefix filter. `ChannelEventBus.emit()` is a no-op on peers with no subscribers for that channel. The observer is not responsible for knowing what the backend subscribes to — coupling the relay filter to `ClaudonyChannelBackend`'s registration filter would be a design smell. Add a filter only if measurable circuit-breaker pressure from non-case channels emerges.

---

## Testing

**`FleetMessageRelayObserverTest` (`@QuarkusTest`, `@Inject FleetMessageRelayObserver observer`):**

Calls `observer.onMessage(event)` directly — `onMessage()` is a plain interface method, not a CDI observer (unlike `ChannelFleetBroadcasterTest` which fires `Event<CaseChannelCreatedEvent>` because the broadcaster is a `@ObservesAsync` CDI observer). Use `MessageType.STATUS` with non-null content to avoid the `content == null` invariant enforced for `MessageType.EVENT`.

`@AfterEach` cleanup (identical to `ChannelFleetBroadcasterTest`):
```
peerRegistry.getAllPeers().stream()
    .filter(p -> p.source() == DiscoverySource.MANUAL)
    .forEach(p -> peerRegistry.removePeer(p.id()));
if (busSubscription != null) busSubscription.cancel();
```

Tests add MANUAL peers — without cleanup they bleed into subsequent tests sharing the same app instance. `ChannelEventBus` subscriptions return a `Cancellable`; without cancellation the `MultiEmitter` stays in the subscribers map indefinitely.

- `onMessage_noPeers_returnsImmediately` — no peers in registry; `assertThatCode(() -> { observer.onMessage(...STATUS...); Thread.sleep(100); }).doesNotThrowAnyException()`
- `onMessage_healthyPeer_ticksChannelEventBusViaLoopback` — add loopback peer at `http://localhost:{testPort}`; `busSubscription = eventBus.subscribe(channelName).subscribe().with(ticks::add)`; call `observer.onMessage(...STATUS...)`; `Thread.sleep(500)`; assert tick received
- `onMessage_peerDown_recordsFailureWithoutCrashing` — add peer at `http://localhost:19999`; call `observer.onMessage(...STATUS...)`; `Thread.sleep(300)`; `assertThatCode(...).doesNotThrowAnyException()`; assert peer still in registry via `peerRegistry.findById()`

**`ChannelSyncResourceTest` additions (`@QuarkusTest`):**

`@AfterEach`: cancel `busSubscription` if non-null.

- `notify_noFleetKey_returns401` — POST `/api/internal/channels/notify` without `X-Api-Key`; assert 401
- `notify_validRequest_returns204_andTicksChannelEventBus` — `busSubscription = eventBus.subscribe(channelName).subscribe().with(ticks::add)`; POST with fleet key; assert 204 and tick received (no sleep — `ChannelEventBus.emit()` is synchronous on the request thread)

**`ChannelBackendDeliveryTest` extension:** deferred. HTTP→bus covered by `ChannelSyncResourceTest`; bus→SSE covered by existing tests. `ChannelPanelE2ETest.channelEvents_pushesMessageInRealTime` tests local delivery only (single-node path via Qhorus dispatch) — the fleet notify endpoint has no E2E coverage, acceptable given unit/integration coverage above.

**Test count:** 520 baseline + 5 new = **525 passing**.

---

## Protocol Alignment

- `PP-20260529-457e5f` (channel backend registration via `ChannelInitialisedEvent`) — not affected; `FleetMessageRelayObserver` is a `MessageObserver`, not a `ChannelBackend`.
- `PP-20260529-68c422` (fleet clients via `.register(FleetKeyClientFilter.class)`) — followed; `relayToPeer()` builds `PeerClient` via `RestClientBuilder.newBuilder().register(FleetKeyClientFilter.class)`.
- `PP-20260529-e418f0` (fleet key → `fleet` role) — followed; `ChannelSyncResource.notify()` uses `@RolesAllowed("fleet")`.

**Garden entry:** GE-20260519-eb8340 (`Instance<MessageObserver>.handles()` wildcard type) — not applicable to the implementor of `FleetMessageRelayObserver`. `MessageObserverDispatcher.dispatch()` already uses `Iterable<? extends Instance.Handle<MessageObserver>>` (the correct wildcard type), so the compile error described in the entry is never encountered when implementing the SPI.
