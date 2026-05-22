# 0007 — SSE Channel Delivery Mechanism

Date: 2026-05-22
Status: Accepted

## Context and Problem Statement

The `/api/mesh/channels/{name}/events` SSE endpoint must deliver new messages to the
browser. The original design used `ChannelEventBus.subscribe()` (event-driven push from
`ClaudonyChannelBackend.post()`). During implementation a cross-thread dispatch issue
prevented frames from being reliably delivered to the browser.

## Decision Drivers

* SSE frames must reliably reach the browser under `@Blocking` endpoint semantics
* E2E test requires delivery within 2 seconds
* `ClaudonyChannelBackend` and `ChannelEventBus` should be retained as future infrastructure
* Operational complexity must remain low

## Considered Options

* **ChannelEventBus-driven push** — `post()` ticks the bus; SSE endpoint subscribes
* **500ms server-side tick** — `Multi.createFrom().ticks().every(500ms)` polls `getTimeline()`
* **SSE `id:` + `Last-Event-ID` reconnect** — client-driven reconnect cursor (future work)

## Decision Outcome

Chosen option: **500ms server-side tick**, because the emitter-based approach had a
cross-thread delivery failure (`emit()` from virtual thread → SSE response thread mismatch)
with no confirmed fix in Mutiny 2.x for `@Blocking` endpoints. The 500ms tick uses
Mutiny's internal scheduler (correct threading) and delivers within the 2s E2E threshold.

### Positive Consequences

* SSE frames reliably delivered; E2E test passes
* `ChannelEventBus` and `ClaudonyChannelBackend` retained for future event-driven upgrade (#131)
* Simple implementation — no emitter lifecycle or cancellation edge cases to manage

### Negative Consequences / Tradeoffs

* Every active SSE connection fires one `getTimeline()` H2 query per 500ms; idle cost
  scales with concurrent connections (acceptable for local H2; revisit for PostgreSQL fleet)
* True push latency not achieved; worst-case 500ms instead of near-zero

## Pros and Cons of the Options

### ChannelEventBus-driven push (original design)

* ✅ True push — near-zero latency after `fanOut()`
* ✅ Zero idle cost — DB query only on actual message arrival
* ❌ Cross-thread dispatch failure with `@Blocking` SSE endpoint and `Multi.createFrom().emitter()` — root cause not confirmed; tracked in claudony#131

### 500ms server-side tick (chosen)

* ✅ Correct threading — Mutiny internal scheduler works with `@Blocking`
* ✅ Simple, testable, no emitter lifecycle to manage
* ❌ Idle DB reads at 500ms intervals per active connection; 500ms worst-case latency

### SSE Last-Event-ID reconnect

* ✅ Browser-native reconnect cursor — no custom cursor logic needed
* ❌ Requires `id:` field in every SSE frame; deferred until delivery is stable (claudony#125)

## Links

* claudony#98 — ClaudonyChannelBackend implementation
* claudony#131 — Follow-on: ChannelEventBus-driven push investigation
* GE-20260522-daca26 — Garden entry: Mutiny emitter cross-thread SSE failure with @Blocking
