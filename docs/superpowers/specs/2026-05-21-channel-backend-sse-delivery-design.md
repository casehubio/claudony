# ClaudonyChannelBackend + SSE Delivery + Restart Re-registration

**Issues:** claudony#98, claudony#101  
**Epic:** epic-gateway-reliability  
**Date:** 2026-05-21

---

## Summary

Implement `ClaudonyChannelBackend` as Qhorus's `HumanObserverChannelBackend` SPI so
the channel panel receives agent messages in real time via SSE rather than 3-second
polling. Register the backend when channels are opened (`ClaudonyCaseChannelProvider`)
and re-register it on server restart (`ServerStartup.bootstrapRegistry()`) — the
#101 core.

---

## Architecture

### New components

**`ClaudonyChannelBackend`** (`@ApplicationScoped`, `claudony-app`)  
Implements `HumanObserverChannelBackend`. Singleton — one instance serves all channels.

- `backendId()` → `"claudony-observer"` (stable constant, no UUID suffix)
- `actorType()` → `ActorType.HUMAN`
- `open(ref, metadata)` → no-op
- `close(ref)` → no-op
- `post(ref, message)` → `channelEventBus.emit(ref.id(), message)`

**`ChannelEventBus`** (`@ApplicationScoped`, `claudony-app`)  
In-process SSE fan-out. Mirrors `EventsOnlyStrategy` pattern.

- State: `ConcurrentHashMap<UUID, List<MultiEmitter<OutboundMessage>>>`
- `Multi<OutboundMessage> subscribe(UUID channelId)` — creates emitter, registers it,
  removes it on termination
- `void emit(UUID channelId, OutboundMessage message)` — fans out to all active emitters

**`GET /api/mesh/channels/{name}/events`** (new endpoint on `MeshResource`)  
Resolves channel name → UUID via `ReactiveChannelService.findByName()`. Returns
`channelEventBus.subscribe(channelId)` serialized to JSON strings as SSE.
Returns 404 if channel not found.

### Modified components

**`ClaudonyReactiveCaseChannelProvider.openChannel()`**  
After channel is found or created: idempotent deregister → `backend.open(ref, {})` →
`gateway.registerBackend(channelId, backend, "human_observer")`.

Injects `ChannelGateway` and `ClaudonyChannelBackend`.

**`ServerStartup.bootstrapRegistry()`** — #101 core  
After tmux session rebuild: for each session with `caseId`, call
`caseChannelProvider.listChannels(caseId).await().indefinitely()`, then for each
channel run the same idempotent deregister → open → register sequence.

**`terminal.js`**  
Replace `chPollTimer` start (in `catchUp().finally()` and `fullLoad().finally()`) with
`openChannelEventSource(name)`. New function:
- Opens `EventSource('/api/mesh/channels/{name}/events')`
- `onmessage`: parse JSON → `appendMessages([entry])`
- `onerror`: close EventSource, start `chPollTimer` as fallback
- `closePanel()` and `selectChannel(null)`: close + nullify EventSource

---

## Data Flow

**Message delivery:**
1. Agent posts → `messageService.send()` persists → `gateway.fanOut(channelId, message)`
2. `fanOut()` → `ClaudonyChannelBackend.post()` (virtual thread)
3. `post()` → `ChannelEventBus.emit(channelId, message)`
4. `emit()` → `emitter.emit(message)` for each active subscriber
5. RESTEasy Reactive pushes JSON SSE frame to browser
6. `EventSource.onmessage` → `appendMessages()` → cursor updated in sessionStorage

**Panel reconnect:**
1. `selectChannel(name)` → cursor found fresh → `catchUp()` fetches missed messages
2. `catchUp().finally()` → `openChannelEventSource(name)` (live from here forward)
3. Subsequent messages flow via steps 1–6 above

**Server restart (#101):**
1. `bootstrapRegistry()` rebuilds session registry from tmux
2. For each session with `caseId`: `listChannels(caseId).await()`
3. Per channel: `deregister("claudony-observer")` → `backend.open()` → `registerBackend()`
4. `gateway.fanOut()` now delivers to panel again

---

## Registration Idempotency

Always call `gateway.deregisterBackend(channelId, "claudony-observer")` before
`registerBackend()`. `deregisterBackend()` is a no-op when the backend is absent (first
registration). This prevents double-registration — `ChannelGateway.registerBackend()` has
no deduplication by `backendId` for `human_observer` type, and a duplicate entry causes
`fanOut()` to call `post()` twice per message.

---

## Error Handling

| Failure | Behaviour |
|---|---|
| EventSource drops | `onerror` fires → close EventSource → start `pollChannel()` fallback |
| `emit()` to cancelled emitter | Emitter's `isCancelled()` check — skip, no-op |
| `bootstrapRegistry()` channel lookup fails | Catch per-caseId, log, continue with others |
| `findByName()` returns empty on SSE connect | Return `Response.status(404)` |
| `post()` exception in `fanOut()` virtual thread | Caught by `ChannelGateway.fanOut()` → logged, non-fatal |

---

## Testing

### Unit tests
- `ChannelEventBusTest` — subscribe/emit/terminate lifecycle; concurrent safety; no-op on empty
- `ClaudonyChannelBackendTest` — `backendId()`, `actorType()`, `post()` delegates, `open()`/`close()` are no-ops

### Integration tests (`@QuarkusTest`)
- `ChannelEventBusIntegrationTest` — subscribe returns Multi that receives emitted items; terminated emitters removed
- `MeshResourceTest` — `meshChannelEvents_unknownChannel_returns404`; `meshChannelEvents_returnsEventStreamContentType`
- `ClaudonyReactiveCaseChannelProviderTest` — `openChannel_registersChannelBackend` (listBackends includes `"claudony-observer"`); `openChannel_deregistersBeforeRegistering` (two calls → one backend entry)
- `ChannelBackendBootstrapTest` — `bootstrapRegistry_registersBackendForCaseIdSessions`; `bootstrapRegistry_skipsSessions_withoutCaseId`

### E2E tests (`-Pe2e`)
- `ChannelPanelE2ETest.channelEvents_pushesMessageToPanel` — post via REST after panel open; verify message appears in < 1s (real-time, not 3s poll cycle)
- EventSource error → poll fallback deferred to #130

---

## Out of Scope

| Concern | Tracked |
|---|---|
| Fleet fan-out (multi-node ClaudonyChannelBackend) | claudony#102 |
| `HumanParticipatingChannelBackend` | claudony#117 |
| SSE `Last-Event-ID` reconnect | claudony#125 |
| Qhorus: ChannelGateway not re-initialized on restart | qhorus#181 |
| EventSource error → fallback E2E test | claudony#130 |
