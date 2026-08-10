# Fleet Channel Backend Delivery — Design Spec

**Issue:** claudony#102  
**Branch:** `issue-102-fleet-channel-backend`  
**Date:** 2026-05-29

---

## Problem

`ClaudonyChannelBackend` is not reliably registered in `ChannelGateway` for case channels. Two symptoms:

1. **Local**: `createQhorusChannel()` calls `channelService.create()` but never calls `gateway.initChannel()`, so `ChannelInitialisedEvent` never fires for runtime-created case channels. `bootstrapChannelBackends()` and `channelEvents()` call `gateway.registerBackend()` directly, which works today (registry entry created via `computeIfAbsent`), but bypasses the Qhorus-idiomatic `ChannelInitialisedEvent` mechanism.

2. **Fleet**: When Node A creates a channel and registers its backend, Node B has no backend registered. Node B's browsers won't receive SSE push updates when #131 (true-push delivery via `ChannelEventBus`) lands.

> **Note on diagnostic test**: `ChannelBackendDeliveryTest` was written to probe this, but routes through `ReactiveMessageService.dispatch()` which does not call `ChannelGateway.fanOut()` (deferred to Qhorus#193). The test was rewritten to call `gateway.fanOut()` directly.

---

## Architecture

Three event sources, one registration path — the `@Observes ChannelInitialisedEvent` observer on `ClaudonyChannelBackend`:

```
A. Startup (all persisted channels)
   ChannelGateway.onStart() → initChannel(each) → ChannelInitialisedEvent
                                                          │
B. Runtime (new case channel)                             ▼
   createQhorusChannel()                       ClaudonyChannelBackend
     └─ channelService.create()               .onChannelInitialised()
     └─ gateway.initChannel()  ──────────────► filters case-*, registers
     └─ Event.fire(CaseChannelCreatedEvent)
                  │
C. Fleet propagation (@ObservesAsync)          ▼ (on each peer)
   ChannelFleetBroadcaster               ChannelSyncResource.sync()
     └─ POST /api/internal/channels/sync   └─ gateway.initChannel()
          to each healthy peer              └─ ChannelInitialisedEvent
                                            └─ observer registers
```

**Invariant**: the only path that registers `ClaudonyChannelBackend` is `ChannelInitialisedEvent`. No direct `registerBackend()` calls anywhere.

---

## `ChannelInitialisedEvent` API

`ChannelInitialisedEvent` is a Qhorus-API record `(UUID channelId, String channelName)`. Confirmed in source. Fired on every `initChannel()` call — including startup recovery and repeated calls for the same channel. The `computeIfAbsent` in `initChannel()` is idempotent; the event fire is not. Callers invoking `initChannel()` multiple times for the same channel trigger the observer repeatedly; because the observer uses deregister-then-register, repeated invocations are safe.

CDI observer registration precedes `@Observes StartupEvent`, so the observer is active before `ChannelGateway.onStart()` runs.

---

## Component Changes

### 1. `CaseChannelCreatedEvent` — new, `claudony-core`

```java
// io.casehub.claudony.server.CaseChannelCreatedEvent
public record CaseChannelCreatedEvent(UUID channelId, String channelName) {}
```

Same package as `WorkerCaseLifecycleEvent`. Fired from `claudony-casehub`, observed in `claudony-app`.

### 2. `ApiKeyAuthMechanism` — grant `fleet` role for fleet key authentication

Currently `ApiKeyAuthMechanism` grants `addRole("user")` for both the agent API key and the fleet key. Change the fleet key path to grant `addRole("fleet")`:

```java
// fleet key path (line ~63):
.setPrincipal(new QuarkusPrincipal("peer"))
.addRole("fleet")   // was: addRole("user")
```

The `user` role grant is removed for fleet key. `@Authenticated` endpoints continue to accept fleet-key callers (any authenticated principal passes). The `FleetKeyAuthTest.fleetKeyAccepted()` test uses `GET /api/sessions` (`@Authenticated`) — unaffected. This change makes the auth model self-documenting: human/agent sessions get `user`, peer-to-peer fleet calls get `fleet`.

### 3. `ClaudonyChannelBackend` — add observer + constructor-inject `ChannelGateway`

Add `ChannelGateway` to the existing constructor injection (keeping the class using constructor injection consistently):

```java
@Inject
public ClaudonyChannelBackend(ChannelEventBus channelEventBus, ChannelGateway gateway) {
    this.channelEventBus = channelEventBus;
    this.gateway = gateway;
}

// initChannel() fires on every call, including repeated calls for the same channel.
// deregister-then-register is idempotent and safe for concurrent restarts.
void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
    if (!event.channelName().startsWith("case-")) return;
    gateway.deregisterBackend(event.channelId(), BACKEND_ID);
    gateway.registerBackend(event.channelId(), this, "human_observer");
}
```

Filters non-case channels. The brief window between deregister and register means a concurrent `fanOut()` sees no `ClaudonyChannelBackend`; for the 500 ms polling SSE this is invisible; for #131 (true push) the tick is lost. Acceptable until #131 is designed.

The permanent registration (no longer deregistered on SSE close) also fixes a pre-existing two-browser race: with the old code, Browser 1 disconnecting fired `deregisterBackend()` for the channel, silently removing the backend for Browser 2 as well. The per-channel lock in `channelEvents()` only protected concurrent opens, not the deregister-on-disconnect path. The new design eliminates this race entirely.

### 4. `ClaudonyReactiveCaseChannelProvider.createQhorusChannel()` — two additions

Inject `ChannelGateway` and `Event<CaseChannelCreatedEvent>`. After `channelService.create()` in the `.map()` callback:

```java
gateway.initChannel(detail.id, new ChannelRef(detail.id, detail.name));
channelCreatedEvent.fire(new CaseChannelCreatedEvent(detail.id, detail.name));
```

- `gateway.initChannel()` fires `ChannelInitialisedEvent` → observer registers backend locally.
- `Event.fire()` (synchronous caller side) with `@ObservesAsync` fleet broadcaster — enqueues async observer and returns immediately; does not block the Vert.x event loop thread. Follows the `WorkerCaseLifecycleEvent` pattern.
- `ChannelGateway` is at `io.casehub.qhorus.runtime.gateway.ChannelGateway` — already a compile dependency of `claudony-casehub` via `casehub-qhorus` runtime artifact.

### 5. `ServerStartup` — remove `bootstrapChannelBackends()` and its dead injections

`ChannelGateway.onStart()` calls `initChannel()` for every channel in the Qhorus store (`channelService.listAll()` — confirmed in source). This fires `ChannelInitialisedEvent` for each channel, and the observer registers the backend for all `case-*` channels automatically. `bootstrapChannelBackends()` is therefore dead code.

**Behavior change**: the old `bootstrapChannelBackends()` filtered by `session.caseId()` — it only registered backends for channels belonging to cases with active sessions. The new design registers backends for ALL `case-*` channels in the Qhorus store, including those whose sessions have terminated. For the current 500 ms polling SSE this is harmless. For #131 (push), terminated-case channels will also receive ticks — probably desired, since a human might still have the panel open after the worker exits.

Remove from `ServerStartup`:
- Method: `bootstrapChannelBackends()`
- Call site in `onStart()`
- Dead field injections (used only by `bootstrapChannelBackends()`): `@Inject ChannelGateway gateway`, `@Inject ClaudonyChannelBackend channelBackend`, `@Inject QhorusDashboardService dashboard`

### 6. `MeshResource.channelEvents()` — remove backend registration and dead injections

Remove:
- The three-line `gateway.deregisterBackend()` / `channelBackend.open()` / `gateway.registerBackend()` registration block
- The `.onTermination().invoke(() -> gateway.deregisterBackend(...))` call
- `channelRegistrationLocks` field (`ConcurrentHashMap<UUID, Object>`)
- Dead field injections (used only in the removed block): `@Inject ClaudonyChannelBackend channelBackend`, `@Inject ChannelGateway gateway`

`ReactiveChannelService channelService` stays — still used for `findByName()` in `channelEvents()`.

### 7. `ChannelSyncResource` — new, `claudony-app` (`fleet` package)

```java
@Path("/api/internal/channels")
public class ChannelSyncResource {
    @Inject ChannelGateway gateway;

    @POST @Path("/sync")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("fleet")
    public Response sync(ChannelSyncRequest request) {
        gateway.initChannel(request.channelId(),
                            new ChannelRef(request.channelId(), request.channelName()));
        return Response.noContent().build();
    }
}

public record ChannelSyncRequest(UUID channelId, String channelName) {}
```

`@RolesAllowed("fleet")` requires the fleet role granted by component 2's change to `ApiKeyAuthMechanism`.

### 8. `PeerClient` — add `syncChannel` method

```java
@POST
@Path("/internal/channels/sync")
@Consumes(MediaType.APPLICATION_JSON)
Response syncChannel(ChannelSyncRequest request);
```

Per GE-20260415-dfa8ba: `RestClientBuilder.newBuilder()` ignores `@RegisterProvider` — register `FleetKeyClientFilter.class` explicitly when building the client.

### 9. `ChannelFleetBroadcaster` — new, `claudony-app` (`fleet` package)

```java
@ApplicationScoped
class ChannelFleetBroadcaster {
    @Inject PeerRegistry peerRegistry;

    void onCaseChannelCreated(@ObservesAsync CaseChannelCreatedEvent event) {
        List<PeerRecord> peers = peerRegistry.getHealthyPeers();
        if (peers.isEmpty()) return;
        var request = new ChannelSyncRequest(event.channelId(), event.channelName());
        for (PeerRecord peer : peers) {
            try {
                PeerClient client = RestClientBuilder.newBuilder()
                        .baseUri(URI.create(peer.url()))
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .register(FleetKeyClientFilter.class)
                        .build(PeerClient.class);
                Response resp = client.syncChannel(request);
                if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
                    peerRegistry.recordSuccess(peer.id());
                } else {
                    peerRegistry.recordFailure(peer.id());
                    LOG.warnf("Channel sync to peer %s returned %d", peer.url(), resp.getStatus());
                }
            } catch (Exception e) {
                peerRegistry.recordFailure(peer.id());
                LOG.warnf("Channel sync to peer %s failed: %s", peer.url(), e.getMessage());
            }
        }
    }
}
```

`connectTimeout(5, SECONDS)` and `readTimeout(5, SECONDS)` match `PeerHealthScheduler`'s pattern. Without timeouts, one slow peer blocks the `@ObservesAsync` executor thread for minutes; peer calls are sequential, so this would stall all subsequent `CaseChannelCreatedEvent` processing.

`@ObservesAsync` fully decouples fleet I/O from the channel creation reactive pipeline. Peer calls are sequential (small fleet: 2–3 nodes). `getHealthyPeers()` already filters OPEN-circuit peers. Failures are non-fatal; `recordFailure()` feeds the circuit breaker.

---

## Error Handling

**Preamble**: `ChannelGateway.fanOut()` delivers only to backends in its in-memory `registry`. This feature uses `initChannel()` exclusively to populate the registry (rather than `registerBackend()` directly) because `initChannel()` fires `ChannelInitialisedEvent`, enabling the self-registration observer across all triggers. The event fires on every `initChannel()` call, including repeated calls — the observer handles this safely via idempotent deregister-then-register.

**Case 1 — Startup recovery after the fix**  
The observer added by this feature does not exist yet. After it is added: `ChannelGateway.onStart()` calls `initChannel()` for every channel in the store (`channelService.listAll()` confirmed in source), firing `ChannelInitialisedEvent` per channel → the observer registers the backend for all `case-*` channels. `bootstrapChannelBackends()` is removed. CDI observer registration precedes `@Observes StartupEvent`, so ordering is guaranteed.

**Case 2 — Deregister-then-register message drop window**  
The observer's deregister-then-register sequence has a brief window where `fanOut()` sees no backend. For the 500 ms polling SSE (current delivery), this is invisible — the next tick fetches from the DB. For the true-push `ChannelEventBus` path (#131), the tick is lost. The window is bounded by two synchronised list operations and is acceptable until #131 is designed.

**Case 3 — `channelService.create()` on a duplicate channel name (retry after cache eviction)**  
If the `layoutCache` is evicted by a failure and `initializeLayout()` retries, `createQhorusChannel()` calls `channelService.create()` for channels that may already exist. Whether `ReactiveChannelService.create()` is idempotent on name collision is a pre-existing question unchanged by this feature. Verify during implementation.

**Case 4 — Cache eviction fires `CaseChannelCreatedEvent` for existing channels**  
After Case 3 eviction, retry fires `CaseChannelCreatedEvent` for already-existing channels. Both listeners are idempotent: the observer deregisters-then-registers (safe); the fleet broadcaster calls `ChannelSyncResource.sync()` which calls `initChannel()` (safe). Redundant work, no correctness problem.

**Case 5 — Partial fleet fan-out failure**  
Non-fatal. Failed peers catch up on next restart via startup recovery. #118 (CLUSTER MessageObserver) provides a second catch-up path independent of per-node backend registration.

---

## Tests

**Infrastructure changes (already committed on this branch):**
- `NoOpWorkloadProvider.java` deleted (stale file — `WorkloadProvider` SPI removed from engine)
- `application.properties`: `WorkerDecisionEventCapture` added to `%test.quarkus.arc.exclude-types` (injected `CaseLedgerEntryRepository` which was already excluded, causing CDI deployment failure)

**Existing test fleet key:** `%test.claudony.fleet-key=test-fleet-key-do-not-use-in-prod` is already configured in `app/src/main/resources/application.properties`. `ChannelSyncResourceTest` sends `X-Api-Key: test-fleet-key-do-not-use-in-prod` — no new test properties needed.

**Deleted:**
- `ChannelBackendBootstrapTest` — tests `bootstrapChannelBackends()` which is removed
- `ChannelBackendDeliveryTest` — rewritten (original tested `ReactiveMessageService` path which doesn't call `fanOut()`)

**Added/replaced:**

`ChannelInitialisedObserverTest` (`@QuarkusTest`) — replaces `ChannelBackendBootstrapTest`:
- `channelInitialised_caseChannel_registersBackend` — `gateway.initChannel()` for `case-*/` channel → backend appears in `gateway.listBackends()`
- `channelInitialised_nonCaseChannel_skipsRegistration` — non-`case-*` channel → not registered
- `channelInitialised_calledTwice_noDuplicates` — idempotent registration check

`ChannelBackendDeliveryTest` (`@QuarkusTest`) — rewritten:
- `fanOut_afterInitChannel_callsPost_ticksEventBus` — seeds channel in store, calls `gateway.initChannel()` (triggers observer), subscribes to `ChannelEventBus`, calls `gateway.fanOut()` directly, waits 100 ms (virtual threads), asserts tick received

`ClaudonyChannelBackendTest` — add:
- `onChannelInitialised_caseChannel_registersBackend`
- `onChannelInitialised_nonCaseChannel_noRegistration`

`ChannelSyncResourceTest` (`@QuarkusTest`):
- `sync_validRequest_returns204_andInitialisesChannel` — sends `X-Api-Key: test-fleet-key-do-not-use-in-prod`, asserts 204 and backend registered
- `sync_noFleetKey_returns401`

`ChannelFleetBroadcasterTest` (`@QuarkusTest`):
- `onCaseChannelCreated_noPeers_doesNothing`
- `onCaseChannelCreated_healthyPeer_callsSyncEndpoint` — peer pointing to `localhost:{test-port}` (loopback, following `SessionFederationTest` pattern); fires `CaseChannelCreatedEvent` via CDI event; waits `Thread.sleep(100)` (matching `ChannelBackendDeliveryTest` precedent for async observer timing); asserts backend registered for the channel
- `onCaseChannelCreated_peerDown_recordsFailure`

`ClaudonyReactiveCaseChannelProviderTest` — add:
- `openChannel_callsInitChannelAfterCreate`
- `openChannel_firesCaseChannelCreatedEvent`

`FleetKeyAuthTest` — update `fleetKeyAccepted()` to also verify fleet-role-gated endpoint returns 200; add `fleetKeyRejectedFromUserEndpoint` if needed after Option B changes role.

**Baseline count impact:** net +7 tests (~2 classes deleted, ~5 new classes added, 4 methods added to existing classes).

---

## Out of Scope

- `ReactiveMessageService.dispatch()` calling `fanOut()` — tracked as Qhorus#193
- True-push SSE delivery via `ChannelEventBus` — tracked as claudony#131
- CLUSTER-scoped `MessageObserver` for cross-node delivery — tracked as claudony#118 (blocked on this issue)
- `channelService.create()` idempotency on name collision — pre-existing behaviour, unchanged
